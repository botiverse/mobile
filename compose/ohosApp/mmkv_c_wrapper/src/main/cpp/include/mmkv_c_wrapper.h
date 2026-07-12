#ifndef SLOCK_MMKV_C_WRAPPER_H
#define SLOCK_MMKV_C_WRAPPER_H

#ifdef __cplusplus
extern "C" {
#endif

signed long mmkv_c_mmkv_with_id(const char *mmap_id, int mode, const char *crypt_key, const char *root_dir,
                                int expected_capacity);
int mmkv_c_set_string(signed long handle, const char *key, const char *value);
char *mmkv_c_get_string(signed long handle, const char *key);
int mmkv_c_contains_key(signed long handle, const char *key);
int mmkv_c_remove_value_for_key(signed long handle, const char *key);

#ifdef __cplusplus
}
#endif

#endif
