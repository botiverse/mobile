import Foundation
import MMKV
import shared

private let slockAuthSessionNamespace = "slockAuthSession"
private let slockLocalPreferenceNamespace = "slockLocal"
private let slockApiEnvironmentNamespace = "slock_api_server_environment"
private let slockPushRegistrationNamespace = "slockPushRegistration"
private let slockActivityUnreadNamespace = "slockActivityUnread"
private let slockIosMobileOAuthNamespace = "slockIosMobileOAuth"
private let slockAppLoginNamespace = "slockAppLogin"

final class SlockIosMMKVKeyValueStore: NSObject, SlockKeyValueStore {
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

enum SlockIosAppScopeMMKV {
    static func install() {
        MMKV.initialize(rootDir: nil)
        [
            slockAuthSessionNamespace,
            slockLocalPreferenceNamespace,
            slockApiEnvironmentNamespace,
            slockPushRegistrationNamespace,
            slockActivityUnreadNamespace,
            slockIosMobileOAuthNamespace,
            slockAppLoginNamespace
        ].forEach { namespace in
            SlockSharedKeyValueStoreProvider.shared.installAppScopeFactory(
                namespace: namespace,
                factory: { SlockIosMMKVKeyValueStore(namespace: namespace) }
            )
        }
    }
}
