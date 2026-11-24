import XCTest
import shared

/// Unit tests for `RaftIosSealedInputsCrypto` (RFC 086 §5.3: P-256 ECDH-ES + RFC 7518 §4.6.2
/// Concat KDF + AES-256-GCM compact JWE), pinned against TEST-ONLY keys and JWE vectors from
/// `sealedInputs.v1.vector.json`.
final class RaftIosSealedInputsHostTests: XCTestCase {
    // TEST ONLY key `test-2026-11-a` from `sealedInputs.v1.vector.json` (never use in production).
    private let keyAX = "_dsSyV6Maib6vDmb1MLPu-31qcVvNkbxUpWAfDnOGcA"
    private let keyAY = "kIEVAwl4P1JF6_ondOiBCJDqXAD0wAM-xXM8OvfmmMM"
    private let keyAD = "9SxcjTv8o1fkN-hVmYYs8T0eN410QRTrrgrr-6HhjuA"

    // TEST ONLY key `test-2026-11-b` from `sealedInputs.v1.vector.json`.
    private let keyBD = "IxI4END0CmM99cO8QTut9KPuVlShg0thtzRBbQDGi3k"

    func testDecryptsSharedVectorOkOneSecretAndUnicodeAndEmpty() throws {
        // `ok_one_secret` from `sealedInputs.v1.vector.json`
        let okOneSecret =
            "eyJhbGciOiJFQ0RILUVTIiwiZW5jIjoiQTI1NkdDTSIsImtpZCI6InRlc3QtMjAyNi0xMS1hIiwiYXVkIjoiYXBwX3Rlc3RfdmF1bHQiLCJzcnYiOiI5NWY5OTNmYS0xYzJkLTRlNWYtOGE5Yi0wYzFkMmUzZjRhNWIiLCJzdWIiOiI0YjFmMGM1ZS04ZDJhLTRlM2ItOWM2Zi03YThiOWMwZDFlMmYiLCJobmQiOiJlMjg2YjBlMWExYmJhNWZlMGE1MDJjYWI0NjA4MThhYWUwNTlhYzNiMjNlNWFhNjUwZmUwYzIxNmJkMDAxZjg3IiwiYWN0Ijoic2F2ZV9rZXkiLCJwaWQiOiIwYjlkNmMzZS0yZjRhLTRiNWMtOGQ2ZS03ZjhhOWIwYzFkMmUiLCJwZGciOiI3NTE0YjY3YjkzZWU0MTdmYzlhMTI1NDZmYzdiYjE4MzNjZDQ4ZWJmYTQxNTFlZmVlNjM3MGE3NDFjY2QyMDk5IiwiY3JxIjoiY3JxLXRlc3QtMjAyNi0xMS0xNC0wMDAxIiwiZXhwIjoxNzk0NzAwODAwLCJlcGsiOnsieCI6IlFOVDBFTnpYWUlmVUFKdXU4b1J2QWd3Y3JBX0M3ejhIdWxkXzNncWtfUTQiLCJjcnYiOiJQLTI1NiIsImt0eSI6IkVDIiwieSI6IkxZNzh0ZTRYWVJhVDE2WEZKeHhmNFNzdXpmQWZ0T29YdDNYbFZ4Y1ZHU2cifX0..URDBK6uXd-tnk_5c.j9EauT_qqxszrotIj8HWFFRyBxxtZ2jRC0BnfHpqKup1dAwV.R9vxUH16mASeO2x8OtuNYA"
        let openedOne = try XCTUnwrap(RaftIosSealedInputsCrypto.openForTests(jwe: okOneSecret, privateKeyDBase64Url: keyAD))
        XCTAssertEqual(openedOne.plaintextJson, "{\"value\":\"sk-test-0123456789abcdef\"}")

        // `ok_two_secrets_unicode` from `sealedInputs.v1.vector.json`
        let okUnicode =
            "eyJhbGciOiJFQ0RILUVTIiwiZW5jIjoiQTI1NkdDTSIsImtpZCI6InRlc3QtMjAyNi0xMS1hIiwiYXVkIjoiYXBwX3Rlc3RfdmF1bHQiLCJzcnYiOiI5NWY5OTNmYS0xYzJkLTRlNWYtOGE5Yi0wYzFkMmUzZjRhNWIiLCJzdWIiOiI0YjFmMGM1ZS04ZDJhLTRlM2ItOWM2Zi03YThiOWMwZDFlMmYiLCJobmQiOiJlMjg2YjBlMWExYmJhNWZlMGE1MDJjYWI0NjA4MThhYWUwNTlhYzNiMjNlNWFhNjUwZmUwYzIxNmJkMDAxZjg3IiwiYWN0Ijoic2F2ZV9rZXkiLCJwaWQiOiIwYjlkNmMzZS0yZjRhLTRiNWMtOGQ2ZS03ZjhhOWIwYzFkMmUiLCJwZGciOiI3NTE0YjY3YjkzZWU0MTdmYzlhMTI1NDZmYzdiYjE4MzNjZDQ4ZWJmYTQxNTFlZmVlNjM3MGE3NDFjY2QyMDk5IiwiY3JxIjoiY3JxLXRlc3QtMjAyNi0xMS0xNC0wMDAxIiwiZXhwIjoxNzk0NzAwODAwLCJlcGsiOnsieCI6IjRPTUxxMUllOXVlSDZmTkFHanVvb2lvaVgzU1FPc3RpWnJJX09BS2N0dkEiLCJjcnYiOiJQLTI1NiIsImt0eSI6IkVDIiwieSI6Im5mR3NBMS1GRTRvXzQ0Q2lIbVkzV0dvVmdENG5YYWQtSzNQbHBIbjk4bEUifX0..5vjSgS3oJSdBAWYS.aiIr9SxyiyKLUpW5ZucW0r5PD3XEPuwgFkBa6sqQkhwKBu6dDI0zDFNGGQHwIpeyR0BRoxiNhiEau1Xv6Cv3s1fH0Q9pGC8.YaAWQopiFmPmoOfZdzE7Rw"
        let openedUnicode = try XCTUnwrap(RaftIosSealedInputsCrypto.openForTests(jwe: okUnicode, privateKeyDBase64Url: keyAD))
        let unicodeObj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(openedUnicode.plaintextJson.utf8)) as? [String: String])
        XCTAssertEqual(unicodeObj["token"], "pässwörd ✓ 🔑")
        XCTAssertEqual(unicodeObj["value"], "汉字 and \"quotes\" \\ back")

        // `ok_no_values` from `sealedInputs.v1.vector.json`
        let okEmpty =
            "eyJhbGciOiJFQ0RILUVTIiwiZW5jIjoiQTI1NkdDTSIsImtpZCI6InRlc3QtMjAyNi0xMS1hIiwiYXVkIjoiYXBwX3Rlc3RfdmF1bHQiLCJzcnYiOiI5NWY5OTNmYS0xYzJkLTRlNWYtOGE5Yi0wYzFkMmUzZjRhNWIiLCJzdWIiOiI0YjFmMGM1ZS04ZDJhLTRlM2ItOWM2Zi03YThiOWMwZDFlMmYiLCJobmQiOiJlMjg2YjBlMWExYmJhNWZlMGE1MDJjYWI0NjA4MThhYWUwNTlhYzNiMjNlNWFhNjUwZmUwYzIxNmJkMDAxZjg3IiwiYWN0Ijoic2F2ZV9rZXkiLCJwaWQiOiIwYjlkNmMzZS0yZjRhLTRiNWMtOGQ2ZS03ZjhhOWIwYzFkMmUiLCJwZGciOiI3NTE0YjY3YjkzZWU0MTdmYzlhMTI1NDZmYzdiYjE4MzNjZDQ4ZWJmYTQxNTFlZmVlNjM3MGE3NDFjY2QyMDk5IiwiY3JxIjoiY3JxLXRlc3QtMjAyNi0xMS0xNC0wMDAxIiwiZXhwIjoxNzk0NzAwODAwLCJlcGsiOnsieCI6IjhjYTh5THM0VE1EUjZjOGQ2Q2R3ZUFfVjBJalFNRGFiNEtmdjIwVXNOeGsiLCJjcnYiOiJQLTI1NiIsImt0eSI6IkVDIiwieSI6IndpbHRTTDRuaTNObG5RYWlxejVuSTJMUnpHU0dhSDNvcldkWUVMMnBHbW8ifX0..QlT2yZILX0AUE1Qt.vAo.bGhyVdzLKn2RQYw1tfB4Ow"
        let openedEmpty = try XCTUnwrap(RaftIosSealedInputsCrypto.openForTests(jwe: okEmpty, privateKeyDBase64Url: keyAD))
        XCTAssertEqual(openedEmpty.plaintextJson, "{}")
    }

    func testSealAndOpenRoundTripPreservesHeaderOrderAndRandomizesCiphertext() throws {
        let headerBase =
            "{\"alg\":\"ECDH-ES\",\"enc\":\"A256GCM\",\"kid\":\"test-2026-11-a\",\"aud\":\"app_test_vault\",\"srv\":\"srv-1\",\"sub\":\"usr-1\",\"hnd\":\"e286b0e1a1bba5fe0a502cab460818aae059ac3b23e5aa650fe0c216bd001f87\",\"act\":\"save_key\",\"pid\":\"pid-1\",\"pdg\":\"pdg-1\",\"crq\":\"crq-1\",\"exp\":1794700800}"
        let plaintext = "{\"token\":\"sk-live-ios-✓-🔑\"}"

        let jwe1 = try XCTUnwrap(
            RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
                publicXBase64Url: keyAX,
                publicYBase64Url: keyAY,
                protectedHeaderBaseJson: headerBase,
                plaintextJson: plaintext
            )
        )
        let jwe2 = try XCTUnwrap(
            RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
                publicXBase64Url: keyAX,
                publicYBase64Url: keyAY,
                protectedHeaderBaseJson: headerBase,
                plaintextJson: plaintext
            )
        )

        XCTAssertNotEqual(jwe1, jwe2, "Each seal must use a fresh ephemeral key and IV")
        let parts = jwe1.split(separator: ".", omittingEmptySubsequences: false)
        XCTAssertEqual(parts.count, 5)
        XCTAssertEqual(parts[1], "", "ECDH-ES direct key agreement must leave encrypted key segment empty")

        let opened = try XCTUnwrap(RaftIosSealedInputsCrypto.openForTests(jwe: jwe1, privateKeyDBase64Url: keyAD))
        XCTAssertEqual(opened.plaintextJson, plaintext)
        XCTAssertTrue(opened.headerJson.hasPrefix(String(headerBase.dropLast()) + ",\"epk\":{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\""))

        // Wrong private key fails closed.
        XCTAssertNil(RaftIosSealedInputsCrypto.openForTests(jwe: jwe1, privateKeyDBase64Url: keyBD))

        // Tampering the protected header (AAD) fails authentication.
        let tamperedHeader = RaftIosSealedInputsCrypto.encodeBase64Url(Data(opened.headerJson.replacingOccurrences(of: "crq-1", with: "crq-2").utf8))
        let tamperedJwe = "\(tamperedHeader)..\(parts[2]).\(parts[3]).\(parts[4])"
        XCTAssertNil(RaftIosSealedInputsCrypto.openForTests(jwe: tamperedJwe, privateKeyDBase64Url: keyAD))
    }

    func testSealRejectsInvalidPublicKeyAndMalformedHeaderBase() {
        let validHeader = "{\"alg\":\"ECDH-ES\",\"enc\":\"A256GCM\",\"kid\":\"k\"}"
        // Off-curve point (x = 0, y = 0)
        let zeroCoord = RaftIosSealedInputsCrypto.encodeBase64Url(Data(repeating: 0, count: 32))
        XCTAssertNil(
            RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
                publicXBase64Url: zeroCoord,
                publicYBase64Url: zeroCoord,
                protectedHeaderBaseJson: validHeader,
                plaintextJson: "{}"
            )
        )
        // Wrong coordinate length
        XCTAssertNil(
            RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
                publicXBase64Url: "AAAA",
                publicYBase64Url: keyAY,
                protectedHeaderBaseJson: validHeader,
                plaintextJson: "{}"
            )
        )
        // Non-canonical base64url with padding
        XCTAssertNil(
            RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
                publicXBase64Url: keyAX + "=",
                publicYBase64Url: keyAY,
                protectedHeaderBaseJson: validHeader,
                plaintextJson: "{}"
            )
        )
        // Malformed header base
        XCTAssertNil(
            RaftIosSealedInputsCrypto.sealEcdhEsA256Gcm(
                publicXBase64Url: keyAX,
                publicYBase64Url: keyAY,
                protectedHeaderBaseJson: "not-json",
                plaintextJson: "{}"
            )
        )
    }

    func testKotlinBridgeSealerConformsAndRoundTrips() throws {
        RaftIosSealedInputsBridge.install()
        let sealer: RaftIosSealedInputsSealer = RaftIosCryptoKitSealedInputsSealer()
        let headerBase = "{\"alg\":\"ECDH-ES\",\"enc\":\"A256GCM\",\"kid\":\"test-2026-11-a\",\"aud\":\"app_test_vault\",\"srv\":\"srv-1\",\"sub\":\"usr-1\",\"hnd\":\"e286b0e1a1bba5fe0a502cab460818aae059ac3b23e5aa650fe0c216bd001f87\",\"act\":\"save_key\",\"crq\":\"crq-bridge\",\"exp\":1794700800}"
        let jwe = try XCTUnwrap(
            sealer.sealEcdhEsA256Gcm(
                publicXBase64Url: keyAX,
                publicYBase64Url: keyAY,
                protectedHeaderBaseJson: headerBase,
                plaintextJson: "{\"secret\":\"hello\"}"
            )
        )
        let opened = try XCTUnwrap(RaftIosSealedInputsCrypto.openForTests(jwe: jwe, privateKeyDBase64Url: keyAD))
        XCTAssertEqual(opened.plaintextJson, "{\"secret\":\"hello\"}")
    }
}
