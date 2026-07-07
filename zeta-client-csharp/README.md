# ZETA SDK - C# Sample Client

## Prerequisites

### Install .NET 10

```bash
brew update
brew install dotnet
```

## Build the native SDK

```bash
cd ~/Workspace/zeta-sdk
./gradlew :zeta-sdk:linkDebugSharedMacosArm64
./gradlew :zeta-sdk:linkDebugSharedMacosX64
./gradlew :zeta-sdk:linkDebugSharedMingwX64
./gradlew :zeta-sdk:linkDebugSharedLinuxX64
```

The library will be at:

```
build/bin/{osArch}/debugShared/
```

## Place the native library (required after each SDK build)

After building the SDK, copy the native library for your target platform into the
`runtimes/` folder before building or running the sample.

| Platform      | Source file                                          | Destination                      |
|---------------|------------------------------------------------------|----------------------------------|
| macOS ARM64   | `build/bin/macosArm64/debugShared/libzeta_sdk.dylib` | `runtimes/osx-arm64/native/`     |
| macOS x64     | `build/bin/macosX64/debugShared/libzeta_sdk.dylib`   | `runtimes/osx-x64/native/`       |
| Linux x64     | `build/bin/linuxX64/debugShared/libzeta_sdk.so`      | `runtimes/linux-x64/native/`     |
| Windows x64   | `build/bin/mingwX64/debugShared/zeta_sdk.dll`        | `runtimes/win-x64/native/`       |

Example for macOS ARM64:

```bash
cp zeta-sdk/build/bin/macosArm64/debugShared/libzeta_sdk.dylib \
   zeta-client-csharp/runtimes/osx-arm64/native/libzeta_sdk.dylib
```

Once the file is in place, uncomment the corresponding `<Content>` entry in
`ZetaSdk.csproj` so the library is included in the build output and NuGet package:

```xml
<ItemGroup>
  <!-- Uncomment the block for your target platform -->

  <!--

  <Content Include="runtimes/osx-arm64/native/libzeta_sdk.dylib">
    <PackagePath>runtimes/osx-arm64/native/libzeta_sdk.dylib</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>

  <Content Include="runtimes/osx-x64/native/libzeta_sdk.dylib">
    <PackagePath>runtimes/osx-x64/native/libzeta_sdk.dylib</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>

  <Content Include="runtimes/linux-x64/native/libzeta_sdk.so">
    <PackagePath>runtimes/linux-x64/native/libzeta_sdk.so</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>

  <Content Include="runtimes/win-x64/native/zeta_sdk.dll">
    <PackagePath>runtimes/win-x64/native/zeta_sdk.dll</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>
  -->
</ItemGroup>
```

## Project structure

```
zeta-client-csharp/
├── zeta-client-csharp.sln      # solution file
├── ZetaSdk.csproj              # class library (NuGet package)
├── ZetaClient.cs               # main SDK entry point
├── ZetaSdkException.cs         # exception types
├── Config/
│   └── ZetaClientConfig.cs     # client configuration model
├── Http/
│   ├── ZetaHttpClient.cs       # synchronous HTTP client wrapper
│   └── ZetaHttpClientAsync.cs  # async HTTP client wrapper
├── Native/
│   ├── NativeMem.cs            # native memory helpers
│   ├── NativeStructs.cs        # P/Invoke struct definitions
│   ├── ZetaNativeLoader.cs     # platform native library loader
│   └── ZetaSdkNative.cs        # P/Invoke declarations
├── Websocket/
│   └── WsSession.cs            # WebSocket / STOMP session
├── runtimes/                   # native libraries (not committed - copy manually)
│   ├── osx-arm64/native/
│   ├── osx-x64/native/
│   ├── linux-x64/native/
│   └── win-x64/native/
├── nupkg/                      # packed NuGet output
│   └── ZetaSdk.Client.0.5.0.nupkg
└── sample/
    ├── sample.csproj           # executable sample
    ├── nuget.config            # local NuGet source config
    └── Program.cs              # sample entry point
```

## Build and run

```bash
cd zeta-client-csharp/sample
POPP_TOKEN="..." \
SMB_KEYSTORE_FILE="/path/to/your.p12" \
SMB_KEYSTORE_ALIAS="alias" \
SMB_KEYSTORE_PASSWORD="00" \
FACHDIENST_URL="https://..." \
ZETA_SCOPES="zero:audience" \
SMCB_BASE_URL="https://..." \
SMCB_MANDANT_ID="..." \
SMCB_CLIENT_SYSTEM_ID="..." \
SMCB_WORKSPACE_ID="..." \
SMCB_USER_ID="..." \
SMCB_CARD_HANDLE="..." \
WS_SERVER_CONTEXT_PATH="/ws" \
dotnet run --project sample.csproj
```

Optional:

```bash
DISABLE_SERVER_VALIDATION=true   # disable TLS validation (dev only)
ASL_PROD=false                   # use RU environment
```

## Pack as NuGet

```bash
dotnet pack ZetaSdk.csproj --configuration Release --output ./nupkg
```

Consume locally from another project:

```bash
dotnet nuget add source ./nupkg --name local-zeta
dotnet add package ZetaSdk.Client
```

The `sample/nuget.config` already points to the local `nupkg/` directory, so
the sample project picks up the package automatically after packing.

Publish to NuGet feed:

```bash
dotnet nuget push nupkg/ZetaSdk.Client.0.5.0.nupkg \
  --source "https://gitlab...." \
  --api-key GITLAB_TOKEN
```

## Supported API

| Method            | Supported |
|-------------------|-----------|
| GET               | yes       |
| POST              | yes       |
| PUT               | yes       |
| PATCH             | yes       |
| DELETE            | yes       |
| HEAD              | yes       |
| OPTIONS           | yes       |
| WebSocket / STOMP | yes       |
