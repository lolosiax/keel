/*
 * Kotlin/Native 自带的 mingw-w64 头文件比 Connector/C 3.4 期望的 Windows SDK 旧：
 *
 * - 缺少 SP_PROT_TLS1_1/1_2/1_3_CLIENT 与 SEC_E_INVALID_PARAMETER；
 * - 没有 Windows 10 的 SCH_CREDENTIALS / TLS_PARAMETERS 结构（TLS 1.3）。
 *
 * 这里按官方 schannel.h 的布局补最小定义，让 Connector/C 的 Schannel 后端能编译。
 * 新 SDK 上这些宏/结构已存在，宏判断会跳过；运行期是否走 TLS 1.3 仍由
 * `MA_SCHANNEL_CREDENTIALS.use_old_cred_structure` 按系统版本决定。
 */
#ifndef KEEL_SCHANNEL_COMPAT_H
#define KEEL_SCHANNEL_COMPAT_H

#ifdef _WIN32

#include <windows.h>
#include <schannel.h>

#ifndef SP_PROT_TLS1_0_CLIENT
#define SP_PROT_TLS1_0_CLIENT 0x00000080
#endif
#ifndef SP_PROT_TLS1_1_CLIENT
#define SP_PROT_TLS1_1_CLIENT 0x00000100
#endif
#ifndef SP_PROT_TLS1_2_CLIENT
#define SP_PROT_TLS1_2_CLIENT 0x00000400
#endif
#ifndef SP_PROT_TLS1_3_CLIENT
#define SP_PROT_TLS1_3_CLIENT 0x00002000
#endif
#ifndef SEC_E_INVALID_PARAMETER
#define SEC_E_INVALID_PARAMETER ((HRESULT) 0x80090305L)
#endif

#ifndef SCH_CREDENTIALS_VERSION

struct _HMAPPER;

typedef struct _TLS_PARAMETERS {
    DWORD cAlpnIds;
    const char **rgstrAlpnIds;
    DWORD grbitDisabledProtocols;
} TLS_PARAMETERS, *PTLS_PARAMETERS;

typedef struct _SCH_CREDENTIALS {
    DWORD dwVersion;
    DWORD dwFlags;
    DWORD cCreds;
    const void *paCred;
    HCERTSTORE hRootStore;
    DWORD cMappers;
    struct _HMAPPER **aphMappers;
    DWORD cSupportedAlgs;
    ALG_ID *palgSupportedAlgs;
    DWORD grbitEnabledProtocols;
    DWORD dwMinimumCipherStrength;
    DWORD dwMaximumCipherStrength;
    DWORD dwSessionLifespan;
    DWORD dwFlagsMask;
    DWORD dwCredFormat;
    DWORD cTlsParameters;
    PTLS_PARAMETERS pTlsParameters;
} SCH_CREDENTIALS, *PSCH_CREDENTIALS;

#define SCH_CREDENTIALS_VERSION 0x00000005

#endif /* SCH_CREDENTIALS_VERSION */

#ifndef SECPKG_ATTR_CIPHER_INFO

#define SECPKGCONTEXT_CIPHERINFO_V1 1
#define SECPKG_ATTR_CIPHER_INFO 0x64
#define SECPKG_ATTR_CIPHER_INFO_MAX_NAME_LEN 256

typedef struct _SecPkgContext_CipherInfo {
    DWORD dwVersion;
    DWORD dwProtocol;
    DWORD dwCipherSuite;
    DWORD dwBaseCipherSuite;
    WCHAR szCipherSuite[SECPKG_ATTR_CIPHER_INFO_MAX_NAME_LEN];
    WCHAR szCipher[SECPKG_ATTR_CIPHER_INFO_MAX_NAME_LEN];
    DWORD dwCipherLen;
    DWORD dwCipherBlockLen;
    DWORD dwKeyLen;
    DWORD dwEffectiveKeyLen;
    DWORD dwSaltLen;
    DWORD dwHashLen;
    WCHAR szHash[SECPKG_ATTR_CIPHER_INFO_MAX_NAME_LEN];
    WCHAR szExchange[SECPKG_ATTR_CIPHER_INFO_MAX_NAME_LEN];
    DWORD dwMinExchangeLen;
    DWORD dwMaxExchangeLen;
    DWORD dwCertificateType;
    DWORD dwProtocolVersion;
    WCHAR szProtocolVersion[SECPKG_ATTR_CIPHER_INFO_MAX_NAME_LEN];
} SecPkgContext_CipherInfo, *PSecPkgContext_CipherInfo;

#endif /* SECPKG_ATTR_CIPHER_INFO */

#endif /* _WIN32 */

#endif /* KEEL_SCHANNEL_COMPAT_H */
