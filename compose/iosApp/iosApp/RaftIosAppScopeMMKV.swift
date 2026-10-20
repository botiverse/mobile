import Foundation
import MMKV
import shared

private let raftAuthSessionNamespace = "slockAuthSession"
private let raftLocalPreferenceNamespace = "slockLocal"
private let raftApiEnvironmentNamespace = "slock_api_server_environment"
private let raftPushRegistrationNamespace = "slockPushRegistration"
private let raftActivityUnreadNamespace = "slockActivityUnread"
private let raftIosMobileOAuthNamespace = "slockIosMobileOAuth"
private let raftAppLoginNamespace = "slockAppLogin"

final class RaftIosMMKVKeyValueStore: NSObject, RaftKeyValueStore {
    private let namespace: String
    private let store: MMKV

    init(namespace: String) {
        self.namespace = namespace
        MMKV.initialize(rootDir: nil)
        self.store = MMKV(mmapID: namespace)!
        super.init()
    }

    func getString(key: String) -> String? {
        let namespacedKey = namespaced(key)
        return store.contains(key: namespacedKey) ? store.string(forKey: namespacedKey) : nil
    }

    func putString(key: String, value: String?) {
        let namespacedKey = namespaced(key)
        if let value {
            store.set(value, forKey: namespacedKey)
        } else {
            store.removeValue(forKey: namespacedKey)
        }
        store.sync()
    }

    private func namespaced(_ key: String) -> String {
        "\(namespace):\(key)"
    }
}

enum RaftIosAppScopeMMKV {
    static func install() {
        MMKV.initialize(rootDir: nil)
        [
            raftAuthSessionNamespace,
            raftLocalPreferenceNamespace,
            raftApiEnvironmentNamespace,
            raftPushRegistrationNamespace,
            raftActivityUnreadNamespace,
            raftIosMobileOAuthNamespace,
            raftAppLoginNamespace
        ].forEach { namespace in
            RaftSharedKeyValueStoreProvider.shared.installAppScopeFactory(
                namespace: namespace,
                factory: { RaftIosMMKVKeyValueStore(namespace: namespace) }
            )
        }
    }
}
