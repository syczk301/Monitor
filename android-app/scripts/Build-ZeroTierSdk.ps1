param(
    [Parameter(Mandatory=$true)][string]$NdkDirectory,
    [string]$SourceDirectory = (Join-Path $PSScriptRoot '..\..\.test-runs\libzt-source'),
    [string]$CMake = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe',
    [string]$Ninja = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe'
)
$ErrorActionPreference = 'Stop'
$source = [IO.Path]::GetFullPath($SourceDirectory)
$ndk = [IO.Path]::GetFullPath($NdkDirectory)
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $androidRoot '..\.test-runs\libzt-build'
$pinned = 'a707ea6ae0910efdc1125d04758c411e2e9ea4f9'
if ((& git -C $source rev-parse HEAD).Trim() -ne $pinned) { throw 'Unexpected SDK source revision' }
& git -C $source submodule status
if ($LASTEXITCODE -ne 0) { throw 'SDK dependencies missing' }
$staging = Join-Path $build 'aar'
New-Item -ItemType Directory -Path (Join-Path $staging 'classes') -Force | Out-Null
$javaFiles = Get-ChildItem -LiteralPath (Join-Path $source 'src\bindings\java\com\zerotier\sockets') -Filter '*.java' | Select-Object -ExpandProperty FullName
& javac --release 8 -d (Join-Path $staging 'classes') @javaFiles
if ($LASTEXITCODE -ne 0) { throw 'SDK Java compilation failed' }
& jar cf (Join-Path $staging 'classes.jar') -C (Join-Path $staging 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'SDK jar packaging failed' }
foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
    $nativeBuild = Join-Path $build $abi
    & $CMake -S $source -B $nativeBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" "-DANDROID_ABI=$abi" '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release' '-DZTS_ENABLE_JAVA=ON' '-DBUILD_HOST_SELFTEST=OFF' '-DZTS_DISABLE_CENTRAL_API=ON' "-DJAVA_INCLUDE_PATH=$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\include" "-DJAVA_INCLUDE_PATH2=$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\include"
    if ($LASTEXITCODE -ne 0) { throw "SDK configure failed: $abi" }
    & $CMake --build $nativeBuild --parallel 8
    if ($LASTEXITCODE -ne 0) { throw "SDK native compilation failed: $abi" }
    & (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe') --strip-unneeded (Join-Path $nativeBuild 'lib\libzt.so')
    if ($LASTEXITCODE -ne 0) { throw "SDK symbol stripping failed: $abi" }
    $destination = Join-Path $staging "jni\$abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $nativeBuild 'lib\libzt.so') -Destination $destination -Force
}
[IO.File]::WriteAllText((Join-Path $staging 'AndroidManifest.xml'), '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zerotier.sockets"><uses-sdk android:minSdkVersion="26" /></manifest>')
$licenses = Join-Path $staging 'META-INF'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'LICENSE.txt') -Destination (Join-Path $licenses 'libzt-LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $source 'ext\ZeroTierOne\LICENSE.txt') -Destination (Join-Path $licenses 'ZeroTierOne-LICENSE.txt') -Force
$assets = Join-Path $androidRoot 'app\src\main\assets\third-party'
New-Item -ItemType Directory -Path $assets -Force | Out-Null
$noticeFiles = @('LICENSE.txt','ext/ZeroTierOne/LICENSE.txt','ext/ZeroTierOne/COPYING','ext/lwip/COPYING','ext/concurrentqueue/LICENSE.md','ext/ZeroTierOne/ext/miniupnpc/LICENSE','ext/ZeroTierOne/ext/libnatpmp/LICENSE','ext/ZeroTierOne/ext/nlohmann/LICENSE.MIT','ext/ZeroTierOne/ext/prometheus-cpp-lite-1.0/LICENSE')
foreach ($notice in $noticeFiles) {
    $name = $notice.Replace('/','-')
    Copy-Item -LiteralPath (Join-Path $source $notice) -Destination (Join-Path $assets $name) -Force
}
$libs = Join-Path $androidRoot 'app\libs'
New-Item -ItemType Directory -Path $libs -Force | Out-Null
& jar cf (Join-Path $libs 'libzt.aar') -C $staging AndroidManifest.xml -C $staging classes.jar -C $staging jni -C $staging META-INF
if ($LASTEXITCODE -ne 0) { throw 'SDK AAR packaging failed' }
Get-FileHash -LiteralPath (Join-Path $libs 'libzt.aar') -Algorithm SHA256
