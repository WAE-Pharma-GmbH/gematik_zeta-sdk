/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

namespace ZetaSdk.Config;

public sealed class ZetaClientConfig
{
    public required string Resource { get; init; }

    public string ProductId      { get; init; } = "zeta-client";
    public string ProductVersion { get; init; } = "0.1.0";
    public string ClientName     { get; init; } = "zeta-cs-client";

    public required ZetaAuthConfig Auth { get; init; }
    public ZetaStorageConfig? Storage { get; init; }
    public ZetaProxyConfig? Proxy { get; init; }
    public Action<string, string?, string>? Logger { get; init; }
    public ZetaLogLevel LogLevel { get; init; } = ZetaLogLevel.Error;
    public SecurityConfig Security { get; init; } = new();
    public NetworkConfig Network { get; init; } = new();
}

public sealed class ZetaAuthConfig
{
    public IReadOnlyList<string> Scopes { get; init; } = ["zero:audience"];
    public int ExpirySeconds { get; init; } = 30;
    public bool AslProdEnvironment { get; init; } = true;
    public ZetaSmbConfig? Smb { get; init; }
    public ICustomSmcbConnector? CustomSmcb { get; init; }
    public required string RequiredRoleOid { get; init; }
}

public sealed class ZetaSmbConfig
{
    public required string KeystoreFile { get; init; }
    public required string Alias        { get; init; }
    public required string Password     { get; init; }
}

public class ZetaStorageConfig
{
    public string? AesB64Key { get; init; }
    public string? StoragePath { get; init; }
    public ICustomStorage? CustomStorage { get; init; }
}

public interface ICustomSmcbConnector
{
    Task<byte[]> ReadCertificateAsync();
    Task<byte[]> ExternalAuthenticateAsync(string base64Challenge);
}

public enum ZetaLogLevel
{
    Debug = 0,
    Info  = 1,
    Warn  = 2,
    Error = 3,
    None  = 4,
}

public sealed class ZetaProxyConfig
{
    public required string  Host     { get; init; }
    public required int     Port     { get; init; }
    public string? Username { get; init; }
    public string? Password { get; init; }
    public ZetaProxyType Type { get; init; } = ZetaProxyType.Http;
}

public enum ZetaProxyType
{
    Http  = 0,
    Socks = 1,
}

public sealed class SecurityConfig
{
    public IReadOnlyList<string> AdditionalCaPem { get; init; } = [];
    public string? AdditionalCaFile { get; init; }
    public bool DisableServerValidation { get; init; }
    public bool SslVerbose { get; init; }
}

public sealed class NetworkConfig
{
    public long ConnectTimeoutMillis { get; init; }
    public long RequestTimeoutMillis { get; init; }
    public long SocketTimeoutMillis { get; init; }
    public int  MaxRetries { get; init; }
    public bool RetryOnlyIdempotent { get; init; } = true;
}
