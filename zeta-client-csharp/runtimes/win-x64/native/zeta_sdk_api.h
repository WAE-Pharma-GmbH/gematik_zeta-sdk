#ifndef KONAN_ZETA_SDK_H
#define KONAN_ZETA_SDK_H
#ifndef ZETA_SDK_H
#define ZETA_SDK_H

#include <stdbool.h>
#include <stdint.h>

/**
 * @file zeta_sdk_api.h
 * @brief ZETA SDK C API
 *
 * This header defines the public C interface of the ZETA SDK.
 * The SDK provides secure authenticated HTTP and WebSocket communication
 * through the ZETA Zero Trust protocol.
 *
 * ## Lifecycle
 * 1. Create a client with ZetaSdk_buildZetaClient()
 * 2. Use ZetaSdk_buildHttpClient() or ZetaSdk_ws() for communication
 * 3. Release all resources with the corresponding _clear/_destroy functions
 *
 * ## Memory Management
 * - All structs passed TO the SDK are owned by the caller
 * - All structs returned FROM the SDK must be freed using the provided destroy functions
 * - String fields (char*) within returned structs are heap-allocated and freed by the destroy function
 */

/**
 * @brief Opaque TPM (Trusted Platform Module) configuration.
 *
 * Reserved for future use. Currently the SDK manages TPM configuration internally.
 * Pass a zero-initialized instance: ZetaSdk_TpmConfig tpm = {};
 */
typedef struct {} ZetaSdk_TpmConfig;
typedef void (*ZetaSdk_VoidCallback)  (void* cbCtx);
typedef void (*ZetaSdk_StringCallback)(void* cbCtx, const char* value);

typedef struct {
    void* context;
    void (*put)   (void* ctx, const char* key, const char* value, ZetaSdk_VoidCallback cb, void* cbCtx);
    void (*get)   (void* ctx, const char* key, ZetaSdk_StringCallback cb, void* cbCtx);
    void (*remove)(void* ctx, const char* key, ZetaSdk_VoidCallback cb, void* cbCtx);
    void (*clear) (void* ctx, ZetaSdk_VoidCallback cb, void* cbCtx);
} ZetaSdk_StorageVTable;

/**
 * @brief Storage configuration for the ZETA SDK.
 *
 * Controls how the SDK persists internal state (keys, tokens, cache).
 * All fields are optional — a zero-initialized instance selects secure defaults:
 *
 *   ZetaSdk_StorageConfig storage = {};
 *
 * Fields:
 *
 *   aesB64Key
 *     Base64-encoded AES key used to encrypt stored data at rest.
 *
 *   storagePath  (Linux only)
 *     Absolute or relative path to the directory used for persistent storage.
 *     NULL = defaults to "$HOME/.zeta_sdk_storage".
 *     Ignored on non-Linux platforms.
 *
 * Platform default backends (when customStorage is NULL):
 *
 *   Linux    — File-based storage under storagePath (or $HOME/.zeta_sdk_storage).
 *              AES-encrypted via aesB64Key.
 *   Windows  — Windows Registry (HKCU\Software\ZetaSDK).
 *              AES-encrypted via aesB64Key.
 *   macOS    — NSUserDefaults / Keychain via the NS storage backend.
 *              AES-encrypted via aesB64Key.
 * Ownership: the SDK does not take ownership of any pointer in this struct.
 * All pointers must remain valid for the lifetime of the SDK instance.
 */
typedef struct {
    char*                  aesB64Key;
    char*                  storagePath;    // Linux only. NULL = $HOME/.zeta_sdk_storage
    ZetaSdk_StorageVTable* customStorage;
} ZetaSdk_StorageConfig;

/**
 * @brief SM-B (Security Module Business) token provider configuration.
 *
 * Used for authentication via a software keystore file (PKCS#12 format).
 * Either SmbConfig or SmcbConfig must be provided in ZetaSdk_AuthConfig, not both.
 *
 * All fields are UTF-8 encoded, null-terminated strings owned by the caller.
 */
typedef struct {
    char* keystoreFile; /**< Path to the PKCS#12 keystore file (.p12 / .pfx) */
    char* alias;        /**< Alias of the key entry within the keystore */
    char* password;     /**< Password to unlock the keystore */
} ZetaSdk_SmbConfig;

/**
 * @brief Callback delivering raw binary data to the SDK.
 *
 * @param cbCtx  Opaque context pointer passed through from the original call.
 * @param data   Pointer to the raw bytes. Valid only for the duration of the callback.
 * @param size   Number of bytes pointed to by data.
 */
typedef void (*ZetaSdk_BytesCallback)(void* cbCtx, const uint8_t* data, int size);


/**
 * @brief VTable for a custom SMC-B connector implementation.
 *
 * Allows C/C++ clients to provide their own SMC-B connector instead of using
 * the built-in connector SOAP implementation. Set this in ZetaSdk_SmcbConfig.customSmcb.
 *
 * Both functions are asynchronous - the SDK suspends until the callback is invoked.
 * The callback MUST be called exactly once per invocation, even on error (pass size=0).
 *
 * Ownership: the SDK does not take ownership of any pointer in this struct.
 * All pointers must remain valid for the lifetime of the SDK instance.
 *
 * Example usage:
 *
 *   void my_read_certificate(void* ctx, ZetaSdk_BytesCallback cb, void* cbCtx) {
 *       std::vector<uint8_t> der = my_connector.getCertificateDer();
 *       cb(cbCtx, der.data(), (int)der.size());
 *   }
 *
 *   void my_external_authenticate(void* ctx, const char* challenge,
 *                                 ZetaSdk_BytesCallback cb, void* cbCtx) {
 *       std::vector<uint8_t> sig = my_connector.sign(challenge);
 *       cb(cbCtx, sig.data(), (int)sig.size());
 *   }
 *
 *   ZetaSdk_SmcbVTable vtable = {
 *       .context              = nullptr,
 *       .readCertificate      = my_read_certificate,
 *       .externalAuthenticate = my_external_authenticate,
 *   };
 */
typedef struct {
    /**
    * Opaque context pointer passed as the first argument to every function.
    * Use this to carry your connector state (e.g. a C++ object pointer).
    */
    void* context;

    /**
    * Read the SMC-B X.509 certificate in DER format.
    *
    * @param ctx    The context pointer from this struct.
    * @param cb     Callback to invoke with the raw DER certificate bytes.
    * @param cbCtx  Opaque context to pass through to the callback unchanged.
    */
    void (*readCertificate)(void* ctx, ZetaSdk_BytesCallback cb, void* cbCtx);

    /**
     * Perform an external authenticate operation (sign a challenge) using the SMC-B.
     *
     * @param ctx             The context pointer from this struct.
     * @param base64Challenge Base64-encoded hash of the token to be signed.
     * @param cb              Callback to invoke with the raw DER-encoded ECDSA signature bytes.
     * @param cbCtx           Opaque context to pass through to the callback unchanged.
     */
    void (*externalAuthenticate)(void* ctx, const char* base64Challenge, ZetaSdk_BytesCallback cb, void* cbCtx);
} ZetaSdk_SmcbVTable;

/**
 * @brief SMC-B (Security Module Card Business) token provider configuration.
 *
 * Used for authentication via a hardware connector
 * Either SmbConfig or SmcbConfig must be provided in ZetaSdk_AuthConfig, not both.
 *
 * All fields are UTF-8 encoded, null-terminated strings owned by the caller.
 */
typedef struct {
    /**
    * Optional custom SMC-B connector implementation.
    *
    * NULL  — use the built-in connector SOAP client with the fields above.
    * !NULL — delegate all SMC-B operations to the provided vtable.
    *         All string fields above are ignored in this case.
    */
    ZetaSdk_SmcbVTable* customSmcb;
} ZetaSdk_SmcbConfig;


/**
 * @brief Callback type for receiving log messages from the ZETA SDK.
 *
 * Called by the SDK whenever a log message is emitted.
 * The strings passed to this callback are valid only for the duration of the call.
 * Do not store pointers to them — copy if needed.
 *
 * @param ctx     Opaque context pointer from ZetaSdk_LogVTable. May be NULL.
 * @param level   Log level string: "DEBUG", "INFO", "WARN" or "ERROR". Never NULL.
 * @param tag     Optional tag identifying the SDK component. May be NULL.
 * @param message The log message. Never NULL.
 */
typedef void (*ZetaSdk_LogCallback)(void* ctx, const char* level, const char* tag, const char* message);

typedef enum {
    ZETA_LOG_LEVEL_DEBUG = 0,
    ZETA_LOG_LEVEL_INFO  = 1,
    ZETA_LOG_LEVEL_WARN  = 2,
    ZETA_LOG_LEVEL_ERROR = 3,
    ZETA_LOG_LEVEL_NONE  = 4,
} ZetaSdk_LogLevel;

/**
 * @brief VTable for a custom log provider implementation.
 *
 * Allows C/C++ clients to receive log output from the ZETA SDK instead of
 * the default stdout output. Set this in ZetaSdk_BuildConfig.logVTable.
 *
 * The callback is invoked synchronously from the SDK's internal threads.
 * Implementations must be thread-safe.
 *
 * Ownership: the SDK does not take ownership of any pointer in this struct.
 * All pointers must remain valid for the lifetime of the SDK instance.
 *
 * Example usage:
 *
 *   void my_log(void* ctx, const char* level, const char* tag, const char* message) {
 *       printf("[%s] [%s] %s\n", level, tag ? tag : "Zeta", message);
 *   }
 *
 *   ZetaSdk_LogVTable logVTable = {
 *       .context = NULL,
 *       .log     = my_log,
 *   };
 */
typedef struct {
    /**
     * Opaque context pointer passed as the first argument to the log callback.
     */
    void* context;

    /**
     * Called for each log message emitted by the SDK.
     *
     * @param ctx     The context pointer from this struct.
     * @param level   Log level: "DEBUG", "INFO", "WARN" or "ERROR".
     * @param tag     Optional component tag. May be NULL.
     * @param message The log message content.
     *
     */
    ZetaSdk_LogCallback log;
    /**
    * Minimum log level the SDK will emit. Messages below this level are suppressed.
    */
    ZetaSdk_LogLevel    logLevel;
} ZetaSdk_LogVTable;


/**
 * @brief Authentication configuration for the ZETA SDK.
 *
 * All string fields are UTF-8 encoded, null-terminated strings owned by the caller.
 */
typedef struct {
    char**            scopes;               /**< Array of OAuth2 scope strings. May be NULL to use server-advertised scopes. */
    int               scopesCount;          /**< Number of entries in the scopes array. */
    int64_t           exp;                  /**< Token expiration time in seconds. Must be greater than 0. */
    bool              aslProdEnvironment;   /**< If true, uses the ASL production environment. Default: true. */
    ZetaSdk_SmbConfig*  smbConfig;          /**< SM-B configuration. Set to NULL when using SMC-B. */
    char*             requiredOid;          /**< Required Role-OID that the TI certificate must contain (e.g. "1.2.276.0.76.4.156" for oid_epa_vau) */
    ZetaSdk_SmcbConfig* smcbConfig;         /**< SMC-B configuration. Set to NULL when using SM-B. */
} ZetaSdk_AuthConfig;

/**
 * @brief proxy configuration for the ZETA SDK.
 */
typedef struct {
    const char* host;     /**< Proxy host, e.g. "proxy.example.com" */
    int         port;     /**< Proxy port, e.g. 8080 */
    const char* username; /**< Optional proxy username. Pass NULL if not required. */
    const char* password; /**< Optional proxy password. Pass NULL if not required. */
    int         type;     /**< Proxy type: 0=HTTP, 1=SOCKS */
} ZetaSdk_ProxyConfig;

/**
 * @brief TLS/certificate security configuration for the ZETA SDK.
 *
 * Pass a zero-initialized instance for strict default validation
 * (system trust store, full chain/SAN/revocation checks).
 *
 */
typedef struct {
    char** additionalCaPem;          /**< Additional trusted CA certs (PEM). */
    int    additionalCaPemCount;     /**< Number of entries in additionalCaPem. */
    char*  additionalCaFile;         /**< Path to a PEM file with additional trusted CAs. NULL = none. */
    bool   disableServerValidation;  /**< Disables all TLS validation. Dev/test only. */
    bool   sslVerbose;               /**< Enables verbose TLS handshake logging. Default: false. */
} ZetaSdk_SecurityConfig;

/**
 * @brief Network timeout and retry configuration for the ZETA SDK.
 *
 * All timeout values are in milliseconds. Pass a zero-initialized instance
 * to use SDK defaults (connect=15000, request=30000, socket=60000).
 *
 */
typedef struct {
    int64_t connectTimeoutMillis;   /**< 0 = use SDK default (15000ms) */
    int64_t requestTimeoutMillis;   /**< 0 = use SDK default (30000ms) */
    int64_t socketTimeoutMillis;    /**< 0 = use SDK default (60000ms) */
    int     maxRetries;             /**< 0 = no retries */
    bool    retryOnlyIdempotent;    /**< Default: true. Ignored if maxRetries == 0. */
} ZetaSdk_NetworkConfig;

/**
 * @brief Top-level build configuration for creating a ZetaSdk_Client.
 *
 * All string fields are UTF-8 encoded, null-terminated strings owned by the caller.
 * All pointer fields must remain valid for the lifetime of the created ZetaSdk_Client.
 */
typedef struct {
    char*                  resource;       /**< Target resource server URL, e.g. "https://fachdienst.example.com/" */
    char*                  productId;      /**< Product identifier of the calling application */
    char*                  productVersion; /**< Version string of the calling application, e.g. "1.0.0" */
    char*                  clientName;     /**< Human-readable name of the calling application */
    ZetaSdk_StorageConfig* storageConfig;  /**< Storage configuration. Pass a zero-initialized instance for defaults. */
    ZetaSdk_TpmConfig*     tpmConfig;      /**< TPM configuration. Pass a zero-initialized instance for defaults. */
    ZetaSdk_AuthConfig*    authConfig;     /**< Authentication configuration. Must not be NULL. */
    ZetaSdk_LogVTable*     logVTable;      /**< Optional custom log provider. */
    ZetaSdk_ProxyConfig*   proxyConfig;    /**< Optional proxy configuration. Pass NULL to disable proxy. */
    ZetaSdk_SecurityConfig* securityConfig; /**< Optional TLS/certificate validation configuration. NULL = strict default validation (system trust store, full chain/SAN/revocation checks). */
    ZetaSdk_NetworkConfig*  networkConfig;  /**< Optional. NULL = SDK defaults for all timeouts/retries. */
} ZetaSdk_BuildConfig;


/**
 * @brief Opaque handle representing an authenticated ZETA SDK client.
 *
 * Created by ZetaSdk_buildZetaClient().
 * Must be released with ZetaSdk_clearZetaClient() when no longer needed.
 * Not thread-safe — external synchronization is required for concurrent access.
 */
typedef struct { void* zetaSdkClient; } ZetaSdk_Client;

/**
 * @brief Opaque handle representing an HTTP client session.
 *
 * Created by ZetaSdk_buildHttpClient().
 * Must be released with ZetaSdk_clearHttpClient() when no longer needed.
 */
typedef struct { void* zetaHttpClient; } ZetaSdk_HttpClient;

/**
 * @brief A single HTTP header key-value pair.
 *
 * Both key and value are UTF-8 encoded, null-terminated strings.
 */
typedef struct {
    char* key;   /**< Header name, e.g. "Content-Type" */
    char* value; /**< Header value, e.g. "application/json" */
} ZetaSdk_HttpHeader;

/**
 * @brief An outgoing HTTP request.
 *
 * All fields are owned by the caller and must remain valid for the duration of the call.
 * body may be NULL for requests without a body (e.g. GET).
 * headers may be NULL if headersCount is 0.
 */
typedef struct {
    char*             url;          /**< Target URL. Must not be NULL. */
    char*             body;         /**< Request body. May be NULL for GET/DELETE requests. */
    ZetaSdk_HttpHeader* headers;    /**< Array of request headers. May be NULL. */
    int               headersCount; /**< Number of entries in the headers array. */
} ZetaSdk_HttpRequest;

/**
 * @brief An HTTP response returned by the SDK.
 *
 * Owned by the SDK. Must be released with ZetaHttpResponse_destroy().
 * On success, error is NULL and status/body/headers are populated.
 * On failure, error contains a description and status may be 0.
 */
typedef struct {
    int               status;       /**< HTTP status code, e.g. 200, 404. 0 indicates a transport-level error. */
    char*             body;         /**< Response body. May be NULL for empty responses. */
    ZetaSdk_HttpHeader* headers;    /**< Array of response headers. May be NULL. */
    int               headersCount; /**< Number of entries in the headers array. */
    char*             error;        /**< Error description on failure. NULL on success. */
} ZetaSdk_HttpResponse;

/**
 * @brief Opaque handle representing an active WebSocket session.
 *
 * Passed to the ZetaSdk_WSHandler callback during a ZetaSdk_ws() call.
 * Valid only within the lifetime of the handler invocation.
 */
typedef struct { void* zetaSdkWsSession; } ZetaSdk_WSSession;

/**
 * @brief Type of a received WebSocket message.
 */
typedef enum {
    WS_TEXT,   /**< Text frame (UTF-8 encoded) */
    WS_BINARY, /**< Binary frame */
    WS_CLOSE   /**< Close frame, the session is being terminated */
} ZetaSdk_WsMessageType;

/**
 * @brief A received WebSocket text message.
 */
typedef struct {
    char* text; /**< UTF-8 encoded, null-terminated text content. */
    int   size; /**< Length of text in bytes, excluding null terminator. */
} ZetaSdk_WSMessage_Text;

/**
 * @brief A received WebSocket binary message.
 */
typedef struct {
    char* bytes; /**< Raw binary content. */
    int   size;  /**< Length of bytes. */
} ZetaSdk_WSMessage_Binary;

/**
 * @brief A received WebSocket message, either text, binary, or close.
 *
 * Check the type field before accessing the data union.
 *
 * @code
 * if (msg.type == WS_TEXT) {
 *     printf("Received: %.*s\n", msg.data.text.size, msg.data.text.text);
 * }
 * @endcode
 */
typedef struct {
    ZetaSdk_WsMessageType type; /**< Discriminator for the data union. */
    union {
        ZetaSdk_WSMessage_Text   text;   /**< Valid when type == WS_TEXT */
        ZetaSdk_WSMessage_Binary binary; /**< Valid when type == WS_BINARY */
    } data;
} ZetaSdk_WSMessage;

/**
 * @brief Callback type for handling an active WebSocket session.
 *
 * Called by ZetaSdk_ws() once the connection is established.
 * The session handle is only valid within this callback.
 * Use ZetaSdk_WSSession_send() to send messages and
 * ZetaSdk_WSSession_receive() to receive messages within this handler.
 *
 * @param session Active WebSocket session handle. Valid only during this call.
 */
typedef void (ZetaSdk_WSHandler)(ZetaSdk_WSSession* session);

#endif /* ZETA_SDK_H */

#ifdef __cplusplus
extern "C" {
#endif
#ifdef __cplusplus
typedef bool            zeta_sdk_KBoolean;
#else
typedef _Bool           zeta_sdk_KBoolean;
#endif
typedef unsigned short     zeta_sdk_KChar;
typedef signed char        zeta_sdk_KByte;
typedef short              zeta_sdk_KShort;
typedef int                zeta_sdk_KInt;
typedef long long          zeta_sdk_KLong;
typedef unsigned char      zeta_sdk_KUByte;
typedef unsigned short     zeta_sdk_KUShort;
typedef unsigned int       zeta_sdk_KUInt;
typedef unsigned long long zeta_sdk_KULong;
typedef float              zeta_sdk_KFloat;
typedef double             zeta_sdk_KDouble;
#ifndef _MSC_VER
typedef float __attribute__ ((__vector_size__ (16))) zeta_sdk_KVector128;
#else
#include <xmmintrin.h>
typedef __m128 zeta_sdk_KVector128;
#endif
typedef void*              zeta_sdk_KNativePtr;
struct zeta_sdk_KType;
typedef struct zeta_sdk_KType zeta_sdk_KType;

typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Byte;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Short;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Int;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Long;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Float;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Double;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Char;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Boolean;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Unit;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_UByte;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_UShort;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_UInt;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_ULong;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_NetworkConfig;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_collections_List;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdk;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClient;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_storage_ResourceScope;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_flow_FlowContextImpl;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_SdkCookieStorage;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_tpm_TpmProvider;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Function1;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClient;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_TpmConfig;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_storage_StorageConfig;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_authentication_AuthConfig;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_attestation_model_PlatformProductId;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClientBuilder;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_RegistrationCallback;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_AuthenticationCallback;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_logging_ZetaLogger;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_kotlin_Any;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus_NOT_REGISTERED;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus_REGISTERED_NO_VALID_TOKENS;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus_HAS_REFRESH_TOKEN;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus_HAS_ACCESS_AND_REFRESH_TOKEN;
typedef struct {
  zeta_sdk_KNativePtr pinned;
} zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_ProxyConfig;

extern void ZetaHttpResponse_destroy(void* httpResponse);
extern zeta_sdk_KInt ZetaSdk_authenticate(void* sdkClient);
extern void* ZetaSdk_buildHttpClient(void* sdkClient);
extern void* ZetaSdk_buildZetaClient(void* buildConfig);
extern void ZetaSdk_clearHttpClient(void* httpClient);
extern zeta_sdk_KInt ZetaSdk_clearRegistration(void* sdkClient);
extern void ZetaSdk_clearZetaClient(void* sdkClient);
extern zeta_sdk_KInt ZetaSdk_close(void* sdkClient);
extern zeta_sdk_KInt ZetaSdk_discover(void* sdkClient);
extern void ZetaSdk_freeLastError(void* ptr);
extern void* ZetaSdk_getLastError();
extern zeta_sdk_KInt ZetaSdk_logout(void* sdkClient);
extern zeta_sdk_KInt ZetaSdk_register(void* sdkClient);
extern zeta_sdk_KInt ZetaSdk_status(void* sdkClient);
extern void* ZetaHttpClient_delete(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_deleteAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void* ZetaHttpClient_get(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_getAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void* ZetaHttpClient_head(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_headAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void* ZetaHttpClient_options(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_optionsAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void* ZetaHttpClient_patch(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_patchAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void* ZetaHttpClient_post(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_postAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void* ZetaHttpClient_put(void* httpClient, void* httpRequest);
extern void ZetaHttpClient_putAsync(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
extern void ZetaSdk_Client_ws(void* sdkClient, void* url, zeta_sdk_KInt urlLen, void* handler, void* customHeaders, zeta_sdk_KInt customHeaderCount);
extern void ZetaSdk_WSMessage_destroy(void* wsMessage);
extern void ZetaSdk_WSSession_close(void* wsSession);
extern void ZetaSdk_WSSession_create(void* sdkClient, void* url, zeta_sdk_KInt urlLen, void* handler);
extern void* ZetaSdk_WSSession_receiveNext(void* wsSession);
extern void ZetaSdk_WSSession_sendBinary(void* wsSession, void* binary, zeta_sdk_KInt size);
extern void ZetaSdk_WSSession_sendText(void* wsSession, void* text, zeta_sdk_KInt textLen);

typedef struct {
  /* Service functions. */
  void (*DisposeStablePointer)(zeta_sdk_KNativePtr ptr);
  void (*DisposeString)(const char* string);
  zeta_sdk_KBoolean (*IsInstance)(zeta_sdk_KNativePtr ref, const zeta_sdk_KType* type);
  zeta_sdk_kref_kotlin_Byte (*createNullableByte)(zeta_sdk_KByte);
  zeta_sdk_KByte (*getNonNullValueOfByte)(zeta_sdk_kref_kotlin_Byte);
  zeta_sdk_kref_kotlin_Short (*createNullableShort)(zeta_sdk_KShort);
  zeta_sdk_KShort (*getNonNullValueOfShort)(zeta_sdk_kref_kotlin_Short);
  zeta_sdk_kref_kotlin_Int (*createNullableInt)(zeta_sdk_KInt);
  zeta_sdk_KInt (*getNonNullValueOfInt)(zeta_sdk_kref_kotlin_Int);
  zeta_sdk_kref_kotlin_Long (*createNullableLong)(zeta_sdk_KLong);
  zeta_sdk_KLong (*getNonNullValueOfLong)(zeta_sdk_kref_kotlin_Long);
  zeta_sdk_kref_kotlin_Float (*createNullableFloat)(zeta_sdk_KFloat);
  zeta_sdk_KFloat (*getNonNullValueOfFloat)(zeta_sdk_kref_kotlin_Float);
  zeta_sdk_kref_kotlin_Double (*createNullableDouble)(zeta_sdk_KDouble);
  zeta_sdk_KDouble (*getNonNullValueOfDouble)(zeta_sdk_kref_kotlin_Double);
  zeta_sdk_kref_kotlin_Char (*createNullableChar)(zeta_sdk_KChar);
  zeta_sdk_KChar (*getNonNullValueOfChar)(zeta_sdk_kref_kotlin_Char);
  zeta_sdk_kref_kotlin_Boolean (*createNullableBoolean)(zeta_sdk_KBoolean);
  zeta_sdk_KBoolean (*getNonNullValueOfBoolean)(zeta_sdk_kref_kotlin_Boolean);
  zeta_sdk_kref_kotlin_Unit (*createNullableUnit)(void);
  zeta_sdk_kref_kotlin_UByte (*createNullableUByte)(zeta_sdk_KUByte);
  zeta_sdk_KUByte (*getNonNullValueOfUByte)(zeta_sdk_kref_kotlin_UByte);
  zeta_sdk_kref_kotlin_UShort (*createNullableUShort)(zeta_sdk_KUShort);
  zeta_sdk_KUShort (*getNonNullValueOfUShort)(zeta_sdk_kref_kotlin_UShort);
  zeta_sdk_kref_kotlin_UInt (*createNullableUInt)(zeta_sdk_KUInt);
  zeta_sdk_KUInt (*getNonNullValueOfUInt)(zeta_sdk_kref_kotlin_UInt);
  zeta_sdk_kref_kotlin_ULong (*createNullableULong)(zeta_sdk_KULong);
  zeta_sdk_KULong (*getNonNullValueOfULong)(zeta_sdk_kref_kotlin_ULong);

  /* User functions. */
  struct {
    struct {
      struct {
        struct {
          struct {
            struct {
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdk (*_instance)();
                zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClient (*build)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdk thiz, const char* resource, zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig config);
              } ZetaSdk;
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl (*ZetaSdkClientImpl)(zeta_sdk_kref_de_gematik_zeta_sdk_storage_ResourceScope resourceScope, zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig cfg);
                zeta_sdk_kref_de_gematik_zeta_sdk_flow_FlowContextImpl (*get_flowContext)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_storage_ResourceScope (*get_resourceScope)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_SdkCookieStorage (*get_sdkCookieStorage)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_tpm_TpmProvider (*get_tpmProvider)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClient (*httpClient)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClientImpl thiz, zeta_sdk_kref_kotlin_Function1 builder);
              } ZetaSdkClientImpl;
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClient (*httpClient)(zeta_sdk_kref_de_gematik_zeta_sdk_ZetaSdkClient thiz, zeta_sdk_kref_kotlin_Function1 builder);
              } ZetaSdkClient;
              struct {
                zeta_sdk_KType* (*_type)(void);
              } TpmConfig;
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig (*BuildConfig)(const char* productId, const char* productVersion, const char* clientName, zeta_sdk_kref_de_gematik_zeta_sdk_storage_StorageConfig storageConfig, zeta_sdk_kref_de_gematik_zeta_sdk_TpmConfig tpmConfig, zeta_sdk_kref_de_gematik_zeta_sdk_authentication_AuthConfig authConfig, zeta_sdk_kref_de_gematik_zeta_sdk_attestation_model_PlatformProductId platformProductId, zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClientBuilder httpClientBuilder, zeta_sdk_kref_de_gematik_zeta_sdk_RegistrationCallback registrationCallback, zeta_sdk_kref_de_gematik_zeta_sdk_AuthenticationCallback authenticationCallback, zeta_sdk_kref_de_gematik_zeta_logging_ZetaLogger logger);
                zeta_sdk_kref_de_gematik_zeta_sdk_authentication_AuthConfig (*get_authConfig)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_AuthenticationCallback (*get_authenticationCallback)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*get_clientName)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClientBuilder (*get_httpClientBuilder)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_logging_ZetaLogger (*get_logger)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_attestation_model_PlatformProductId (*get_platformProductId)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*get_productId)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*get_productVersion)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_RegistrationCallback (*get_registrationCallback)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_storage_StorageConfig (*get_storageConfig)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_TpmConfig (*get_tpmConfig)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*component1)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_AuthenticationCallback (*component10)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_logging_ZetaLogger (*component11)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*component2)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*component3)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_storage_StorageConfig (*component4)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_TpmConfig (*component5)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_authentication_AuthConfig (*component6)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_attestation_model_PlatformProductId (*component7)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClientBuilder (*component8)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_RegistrationCallback (*component9)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig (*copy)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz, const char* productId, const char* productVersion, const char* clientName, zeta_sdk_kref_de_gematik_zeta_sdk_storage_StorageConfig storageConfig, zeta_sdk_kref_de_gematik_zeta_sdk_TpmConfig tpmConfig, zeta_sdk_kref_de_gematik_zeta_sdk_authentication_AuthConfig authConfig, zeta_sdk_kref_de_gematik_zeta_sdk_attestation_model_PlatformProductId platformProductId, zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_ZetaHttpClientBuilder httpClientBuilder, zeta_sdk_kref_de_gematik_zeta_sdk_RegistrationCallback registrationCallback, zeta_sdk_kref_de_gematik_zeta_sdk_AuthenticationCallback authenticationCallback, zeta_sdk_kref_de_gematik_zeta_logging_ZetaLogger logger);
                zeta_sdk_KBoolean (*equals)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz, zeta_sdk_kref_kotlin_Any other);
                zeta_sdk_KInt (*hashCode)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
                const char* (*toString)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz);
              } BuildConfig;
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo (*RegInfo)(const char* clientName);
                const char* (*get_clientName)(zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo thiz);
                const char* (*component1)(zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo (*copy)(zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo thiz, const char* clientName);
                zeta_sdk_KBoolean (*equals)(zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo thiz, zeta_sdk_kref_kotlin_Any other);
                zeta_sdk_KInt (*hashCode)(zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo thiz);
                const char* (*toString)(zeta_sdk_kref_de_gematik_zeta_sdk_RegInfo thiz);
              } RegInfo;
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo (*AuthInfo)(const char* otp);
                const char* (*get_otp)(zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo thiz);
                const char* (*component1)(zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo (*copy)(zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo thiz, const char* otp);
                zeta_sdk_KBoolean (*equals)(zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo thiz, zeta_sdk_kref_kotlin_Any other);
                zeta_sdk_KInt (*hashCode)(zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo thiz);
                const char* (*toString)(zeta_sdk_kref_de_gematik_zeta_sdk_AuthInfo thiz);
              } AuthInfo;
              struct {
                zeta_sdk_KType* (*_type)(void);
              } RegistrationCallback;
              struct {
                zeta_sdk_KType* (*_type)(void);
              } AuthenticationCallback;
              struct {
                struct {
                  zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus (*get)(); /* enum entry for NOT_REGISTERED. */
                } NOT_REGISTERED;
                struct {
                  zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus (*get)(); /* enum entry for REGISTERED_NO_VALID_TOKENS. */
                } REGISTERED_NO_VALID_TOKENS;
                struct {
                  zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus (*get)(); /* enum entry for HAS_REFRESH_TOKEN. */
                } HAS_REFRESH_TOKEN;
                struct {
                  zeta_sdk_kref_de_gematik_zeta_sdk_SdkStatus (*get)(); /* enum entry for HAS_ACCESS_AND_REFRESH_TOKEN. */
                } HAS_ACCESS_AND_REFRESH_TOKEN;
                zeta_sdk_KType* (*_type)(void);
              } SdkStatus;
              struct {
                zeta_sdk_KType* (*_type)(void);
                zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig (*NativeHttpSecurityConfig)(zeta_sdk_kref_kotlin_collections_List additionalCaPem, const char* additionalCaFile, zeta_sdk_KBoolean disableServerValidation, zeta_sdk_KBoolean sslVerbose, zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_ProxyConfig proxyConfig);
                const char* (*get_additionalCaFile)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_kref_kotlin_collections_List (*get_additionalCaPem)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_KBoolean (*get_disableServerValidation)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_ProxyConfig (*get_proxyConfig)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_KBoolean (*get_sslVerbose)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_kref_kotlin_collections_List (*component1)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                const char* (*component2)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_KBoolean (*component3)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_KBoolean (*component4)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_ProxyConfig (*component5)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig (*copy)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz, zeta_sdk_kref_kotlin_collections_List additionalCaPem, const char* additionalCaFile, zeta_sdk_KBoolean disableServerValidation, zeta_sdk_KBoolean sslVerbose, zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_ProxyConfig proxyConfig);
                zeta_sdk_KBoolean (*equals)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz, zeta_sdk_kref_kotlin_Any other);
                zeta_sdk_KInt (*hashCode)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
                const char* (*toString)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig thiz);
              } NativeHttpSecurityConfig;
              zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig (*withNamespace)(zeta_sdk_kref_de_gematik_zeta_sdk_BuildConfig thiz, const char* namespace_);
              zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig (*get_globalHttpSecurityConfig)();
              void (*set_globalHttpSecurityConfig)(zeta_sdk_kref_de_gematik_zeta_sdk_NativeHttpSecurityConfig set);
              zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_NetworkConfig (*get_globalNetworkConfig)();
              void (*set_globalNetworkConfig)(zeta_sdk_kref_de_gematik_zeta_sdk_network_http_client_config_NetworkConfig set);
              void (*ZetaHttpResponse_destroy_)(void* httpResponse);
              zeta_sdk_KInt (*ZetaSdk_authenticate_)(void* sdkClient);
              void* (*ZetaSdk_buildHttpClient_)(void* sdkClient);
              void* (*ZetaSdk_buildSdkClient)(void* buildConfig);
              void (*ZetaSdk_clearHttpClient_)(void* httpClient);
              zeta_sdk_KInt (*ZetaSdk_clearRegistration_)(void* sdkClient);
              void (*ZetaSdk_clearZetaClient_)(void* sdkClient);
              zeta_sdk_KInt (*ZetaSdk_close_)(void* sdkClient);
              zeta_sdk_KInt (*ZetaSdk_discover_)(void* sdkClient);
              void (*ZetaSdk_freeLastError_)(void* ptr);
              void* (*ZetaSdk_getLastError_)();
              zeta_sdk_KInt (*ZetaSdk_logout_)(void* sdkClient);
              zeta_sdk_KInt (*ZetaSdk_register_)(void* sdkClient);
              zeta_sdk_KInt (*ZetaSdk_status_)(void* sdkClient);
              zeta_sdk_kref_kotlin_collections_List (*toKList)(void* thiz, zeta_sdk_KInt count);
              zeta_sdk_kref_kotlin_collections_List (*toKList_)(void* thiz, zeta_sdk_KInt count);
              void* (*ZetaHttpClient_delete_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_deleteAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void* (*ZetaHttpClient_get_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_getAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void* (*ZetaHttpClient_head_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_headAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void* (*ZetaHttpClient_options_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_optionsAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void* (*ZetaHttpClient_patch_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_patchAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void* (*ZetaHttpClient_post_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_postAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void* (*ZetaHttpClient_put_)(void* httpClient, void* httpRequest);
              void (*ZetaHttpClient_putAsync_)(void* httpClient, void* httpRequest, void* onSuccess, void* onError);
              void (*ZetaSdk_Client_ws_)(void* sdkClient, void* url, zeta_sdk_KInt urlLen, void* handler, void* customHeaders, zeta_sdk_KInt customHeaderCount);
              void (*ZetaSdk_WSMessage_destroy_)(void* wsMessage);
              void (*ZetaSdk_WSSession_close_)(void* wsSession);
              void (*ZetaSdk_WSSession_create_)(void* sdkClient, void* url, zeta_sdk_KInt urlLen, void* handler);
              void* (*ZetaSdk_WSSession_receiveNext_)(void* wsSession);
              void (*ZetaSdk_WSSession_sendBinary_)(void* wsSession, void* binary, zeta_sdk_KInt size);
              void (*ZetaSdk_WSSession_sendText_)(void* wsSession, void* text, zeta_sdk_KInt textLen);
            } sdk;
          } zeta;
        } gematik;
      } de;
    } root;
  } kotlin;
} zeta_sdk_ExportedSymbols;
extern zeta_sdk_ExportedSymbols* zeta_sdk_symbols(void);
#ifdef __cplusplus
}  /* extern "C" */
#endif
#endif  /* KONAN_ZETA_SDK_H */
