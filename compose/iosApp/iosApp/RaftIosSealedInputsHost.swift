import CryptoKit
import Foundation
import shared

/// Pure Apple CryptoKit implementation of RFC 086 §5.3 (`alg: "ECDH-ES"`, `enc: "A256GCM"`, P-256)
/// compact JWE sealing with RFC 7518 §4.6.2 Concat KDF (SHA-256).
///
/// Never logs or exposes plaintext values or JWE strings in errors.
enum RaftIosSealedInputsCrypto {
    private static let coordinateBytes = 32
    private static let ivBytes = 12
    private static let tagBytes = 16
    private static let algorithmIdBytes = Data("A256GCM".utf8)
    private static let keyDataLenBits: UInt32 = 256

    static func sealEcdhEsA256Gcm(
        publicXBase64Url: String,
        publicYBase64Url: String,
        protectedHeaderBaseJson: String,
        plaintextJson: String
    ) -> String? {
        guard protectedHeaderBaseJson.hasPrefix("{"), protectedHeaderBaseJson.hasSuffix("}") else {
            return nil
        }
        guard let xData = decodeBase64UrlCanonical(publicXBase64Url), xData.count == coordinateBytes,
              let yData = decodeBase64UrlCanonical(publicYBase64Url), yData.count == coordinateBytes else {
            return nil
        }
        do {
            var x963 = Data(capacity: 1 + coordinateBytes * 2)
            x963.append(0x04)
            x963.append(xData)
            x963.append(yData)
            let recipientPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: x963)

            let ephemeralPrivateKey = P256.KeyAgreement.PrivateKey()
            let ephemeralRaw = ephemeralPrivateKey.publicKey.rawRepresentation
            guard ephemeralRaw.count == coordinateBytes * 2 else {
                return nil
            }
            let epkX = encodeBase64Url(ephemeralRaw.prefix(coordinateBytes))
            let epkY = encodeBase64Url(ephemeralRaw.suffix(coordinateBytes))

            let headerPrefix = String(protectedHeaderBaseJson.dropLast())
            let fullHeaderJson = "\(headerPrefix),\"epk\":{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"\(epkX)\",\"y\":\"\(epkY)\"}}"
            let protectedB64 = encodeBase64Url(Data(fullHeaderJson.utf8))

            let sharedSecret = try ephemeralPrivateKey.sharedSecretFromKeyAgreement(with: recipientPublicKey)
            let zData = sharedSecret.withUnsafeBytes { Data($0) }
            guard zData.count == coordinateBytes else {
                return nil
            }
            let cek = concatKdfA256Gcm(sharedSecret: zData)

            let nonce = AES.GCM.Nonce()
            let aad = Data(protectedB64.utf8)
            let plaintext = Data(plaintextJson.utf8)
            let sealedBox = try AES.GCM.seal(
                plaintext,
                using: SymmetricKey(data: cek),
                nonce: nonce,
                authenticating: aad
            )

            let ivB64 = encodeBase64Url(Data(sealedBox.nonce))
            let ctB64 = encodeBase64Url(sealedBox.ciphertext)
            let tagB64 = encodeBase64Url(sealedBox.tag)
            return "\(protectedB64)..\(ivB64).\(ctB64).\(tagB64)"
        } catch {
            return nil
        }
    }

    /// Test-only helper to decrypt an `ECDH-ES` / `A256GCM` compact JWE with a P-256 private scalar `d`.
    static func openForTests(
        jwe: String,
        privateKeyDBase64Url: String
    ) -> (headerJson: String, plaintextJson: String)? {
        let parts = jwe.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 5, parts[1].isEmpty else {
            return nil
        }
        guard let dData = decodeBase64UrlCanonical(privateKeyDBase64Url), dData.count == coordinateBytes,
              let headerData = decodeBase64UrlCanonical(String(parts[0])),
              let headerJson = String(data: headerData, encoding: .utf8),
              let ivData = decodeBase64UrlCanonical(String(parts[2])), ivData.count == ivBytes,
              let ctData = decodeBase64UrlCanonical(String(parts[3])),
              let tagData = decodeBase64UrlCanonical(String(parts[4])), tagData.count == tagBytes else {
            return nil
        }
        guard let headerObj = (try? JSONSerialization.jsonObject(with: headerData)) as? [String: Any],
              (headerObj["alg"] as? String) == "ECDH-ES",
              (headerObj["enc"] as? String) == "A256GCM",
              headerObj["zip"] == nil,
              let epk = headerObj["epk"] as? [String: Any],
              (epk["kty"] as? String) == "EC",
              (epk["crv"] as? String) == "P-256",
              let epkX = epk["x"] as? String,
              let epkY = epk["y"] as? String,
              let epkXData = decodeBase64UrlCanonical(epkX), epkXData.count == coordinateBytes,
              let epkYData = decodeBase64UrlCanonical(epkY), epkYData.count == coordinateBytes else {
            return nil
        }
        do {
            var epkX963 = Data(capacity: 1 + coordinateBytes * 2)
            epkX963.append(0x04)
            epkX963.append(epkXData)
            epkX963.append(epkYData)
            let epkPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: epkX963)
            let recipientPrivateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: dData)

            let sharedSecret = try recipientPrivateKey.sharedSecretFromKeyAgreement(with: epkPublicKey)
            let zData = sharedSecret.withUnsafeBytes { Data($0) }
            guard zData.count == coordinateBytes else {
                return nil
            }
            let cek = concatKdfA256Gcm(sharedSecret: zData)

            let nonce = try AES.GCM.Nonce(data: ivData)
            let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ctData, tag: tagData)
            let aad = Data(parts[0].utf8)
            let plaintextData = try AES.GCM.open(sealedBox, using: SymmetricKey(data: cek), authenticating: aad)
            guard let plaintextJson = String(data: plaintextData, encoding: .utf8) else {
                return nil
            }
            return (headerJson: headerJson, plaintextJson: plaintextJson)
        } catch {
            return nil
        }
    }

    /// RFC 7518 §4.6.2 Concat KDF with SHA-256 for `alg="ECDH-ES"`, `enc="A256GCM"`, empty `apu`/`apv`.
    private static func concatKdfA256Gcm(sharedSecret: Data) -> Data {
        var input = Data(capacity: 4 + sharedSecret.count + 4 + algorithmIdBytes.count + 4 + 4 + 4)
        appendUInt32BE(&input, 1) // Round 1
        input.append(sharedSecret)
        appendUInt32BE(&input, UInt32(algorithmIdBytes.count))
        input.append(algorithmIdBytes)
        appendUInt32BE(&input, 0) // PartyUInfo (empty)
        appendUInt32BE(&input, 0) // PartyVInfo (empty)
        appendUInt32BE(&input, keyDataLenBits) // SuppPubInfo (256 bits)
        return Data(SHA256.hash(data: input))
    }

    private static func appendUInt32BE(_ data: inout Data, _ value: UInt32) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    static func encodeBase64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decodeBase64UrlCanonical(_ value: String) -> Data? {
        guard !value.isEmpty else { return Data() }
        for scalar in value.unicodeScalars {
            let v = scalar.value
            let valid =
                (v >= 0x41 && v <= 0x5A) || // A-Z
                (v >= 0x61 && v <= 0x7A) || // a-z
                (v >= 0x30 && v <= 0x39) || // 0-9
                v == 0x2D || // -
                v == 0x5F    // _
            if !valid { return nil }
        }
        let remainder = value.count % 4
        if remainder == 1 { return nil }
        var standard = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        if remainder > 0 {
            standard.append(String(repeating: "=", count: 4 - remainder))
        }
        guard let decoded = Data(base64Encoded: standard),
              encodeBase64Url(decoded) == value else {
            return nil
        }
        return decoded
    }
}

final class RaftIosCryptoKitSealedInputsSealer: NSObject, RaftIosSealedInputsSealer {
    func sealEcdhEsA256Gcm(
        publicXBase64Url: String,
        publicYBase64Url: String,
        protectedHeaderBaseJson: String,
        plaintextJson: String
    ) -> String? {
        RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
            publicXBase64Url: publicXBase64Url,
            publicYBase64Url: publicYBase64Url,
            protectedHeaderBaseJson: protectedHeaderBaseJson,
            plaintextJson: plaintextJson
        )
    }
}

enum RaftIosSealedInputsBridge {
    private static let sealer = RaftIosCryptoKitSealedInputsSealer()

    static func install() {
        RaftIosSealedInputsHost.shared.install(sealer: sealer)
    }
}
