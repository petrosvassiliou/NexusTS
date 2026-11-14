#ifndef NEXUSTS_FFI_H
#define NEXUSTS_FFI_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct NexusFfiBuffer {
    uint8_t* data;
    size_t len;
    size_t cap;
    char* error_msg;
} NexusFfiBuffer;

void nexusts_ffi_free_buffer(NexusFfiBuffer buf);
void nexusts_ffi_free_cstr(char* ptr);

/* --- .nxts v2 Zero-Knowledge Vault Cryptography & OpenSSH Ed25519 Keygen --- */

NexusFfiBuffer nexusts_ffi_seal_vault(
    const uint8_t* plaintext_ptr,
    size_t plaintext_len,
    const uint8_t* passphrase_ptr,
    size_t passphrase_len,
    uint64_t revision,
    uint32_t memory_kib,
    uint32_t iterations,
    uint32_t parallelism
);

NexusFfiBuffer nexusts_ffi_open_vault(
    const uint8_t* container_ptr,
    size_t container_len,
    const uint8_t* passphrase_ptr,
    size_t passphrase_len,
    uint64_t* out_revision
);

char* nexusts_ffi_generate_ed25519_keypair_json(const char* comment_cstr);

NexusFfiBuffer nexusts_ffi_ssh_agent_sign(
    const char* key_id_cstr,
    const uint8_t* challenge_ptr,
    size_t challenge_len
);

void nexusts_ffi_ssh_agent_clear(void);

/* --- xterm-256color / 24-bit TrueColor VT Terminal Engine & Telnet RFC 854 --- */

int64_t nexusts_ffi_vt_create(uint32_t cols, uint32_t rows, uint32_t max_scrollback);
void nexusts_ffi_vt_resize(int64_t handle, uint32_t cols, uint32_t rows);
char* nexusts_ffi_vt_feed_and_snapshot_json(int64_t handle, const uint8_t* data_ptr, size_t data_len);
char* nexusts_ffi_vt_snapshot_json(int64_t handle);
void nexusts_ffi_vt_destroy(int64_t handle);

NexusFfiBuffer nexusts_ffi_telnet_process(
    uint16_t cols,
    uint16_t rows,
    const char* term_cstr,
    const uint8_t* wire_ptr,
    size_t wire_len,
    NexusFfiBuffer* out_replies
);

/* --- Async SSHv2 (russh), Bastion Chaining, Port Forwards & SFTP v3 --- */

int64_t nexusts_ffi_ssh_connect_json(
    const char* connect_spec_json_cstr,
    const char* bastion_spec_json_cstr,
    bool open_pty,
    char** out_error
);

bool nexusts_ffi_ssh_send_input(int64_t session_handle, const uint8_t* bytes_ptr, size_t bytes_len);
NexusFfiBuffer nexusts_ffi_ssh_drain_output(int64_t session_handle, bool* out_eof);
bool nexusts_ffi_ssh_resize_pty(int64_t session_handle, uint32_t cols, uint32_t rows);

bool nexusts_ffi_ssh_start_local_forward(
    int64_t session_handle,
    const char* bind_addr_cstr,
    uint16_t bind_port,
    const char* target_host_cstr,
    uint16_t target_port,
    char** out_error
);

bool nexusts_ffi_ssh_start_socks5_forward(
    int64_t session_handle,
    const char* bind_addr_cstr,
    uint16_t bind_port,
    char** out_error
);

void nexusts_ffi_ssh_disconnect(int64_t session_handle);

int64_t nexusts_ffi_sftp_open_on_ssh(int64_t ssh_session_handle, char** out_error);
char* nexusts_ffi_sftp_list_dir_json(int64_t sftp_handle, const char* remote_dir_cstr, char** out_error);
bool nexusts_ffi_sftp_mkdir(int64_t sftp_handle, const char* remote_path_cstr, char** out_error);
bool nexusts_ffi_sftp_remove(int64_t sftp_handle, const char* remote_path_cstr, bool is_dir, char** out_error);
bool nexusts_ffi_sftp_rename(int64_t sftp_handle, const char* old_path_cstr, const char* new_path_cstr, char** out_error);
NexusFfiBuffer nexusts_ffi_sftp_download(int64_t sftp_handle, const char* remote_path_cstr);
bool nexusts_ffi_sftp_upload(int64_t sftp_handle, const char* remote_path_cstr, const uint8_t* data_ptr, size_t data_len, char** out_error);
void nexusts_ffi_sftp_close(int64_t sftp_handle);

char* nexusts_ffi_parse_ftp_mlsd_json(const char* parent_dir_cstr, const char* mlsd_cstr);

/* --- RFB / VNC / Apple Screen Sharing Framebuffer --- */

int64_t nexusts_ffi_vnc_create(uint32_t width, uint32_t height);
void nexusts_ffi_vnc_resize(int64_t handle, uint32_t width, uint32_t height);
bool nexusts_ffi_vnc_apply_rect(
    int64_t handle,
    int32_t encoding,
    uint16_t x,
    uint16_t y,
    uint16_t w,
    uint16_t h,
    const uint8_t* payload_ptr,
    size_t payload_len,
    char** out_error
);
size_t nexusts_ffi_vnc_copy_rgba(int64_t handle, uint8_t* dst_ptr, size_t dst_cap);
void nexusts_ffi_vnc_destroy(int64_t handle);

/* --- Integrated Network Diagnostics (WoL, Port Probe, Traceroute, Banner) --- */

int32_t nexusts_ffi_net_wol(
    const char* mac_cstr,
    const char* broadcast_ip_cstr,
    uint16_t udp_port,
    const char* secure_on_cstr,
    char** out_error
);
char* nexusts_ffi_net_probe_tcp_json(const char* host_cstr, uint16_t port, uint64_t timeout_ms);
char* nexusts_ffi_net_traceroute_json(const char* host_cstr, uint16_t probe_port, uint8_t max_hops, uint64_t timeout_ms);
char* nexusts_ffi_net_grab_banner(const char* host_cstr, uint16_t port, uint64_t timeout_ms);

#ifdef __cplusplus
}
#endif

#endif /* NEXUSTS_FFI_H */
