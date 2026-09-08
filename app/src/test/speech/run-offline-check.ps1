param([switch]$IncludeAndroid)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$cacheRoot = Join-Path $projectRoot '_tools/gradle_home/cache/caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2/files-2.1'
$speechJava = Join-Path $projectRoot '_tools/jdk/jdk-17.0.20.1+1/bin/java.exe'
$taskOutput = Join-Path ([IO.Path]::GetTempPath()) ('StarBase-speech-check-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $taskOutput

function Get-Jar([string]$group, [string]$artifact, [string]$version) {
    $path = Join-Path $moduleRoot "$group/$artifact/$version"
    $jar = Get-ChildItem -LiteralPath $path -Recurse -Filter '*.jar' | Select-Object -First 1
    if (-not $jar) { throw "Missing cached dependency: $group/$artifact/$version" }
    return $jar.FullName
}

$stdlib = Get-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.3.21'
$annotations = Get-Jar 'org.jetbrains' 'annotations' '13.0'
$coroutines = Get-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.10.2'
$compilerClasspath = @(
    (Get-Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.3.21')
    (Get-Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.3.21')
    (Get-Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10')
    $stdlib
    $annotations
    $coroutines
) -join ';'
$dependencies = @(
    $stdlib
    $annotations
    $coroutines
    (Get-Jar 'com.squareup.okhttp3' 'okhttp' '4.12.0')
    (Get-Jar 'com.squareup.okio' 'okio-jvm' '3.9.0')
    (Get-Jar 'junit' 'junit' '4.13.2')
    (Get-Jar 'org.hamcrest' 'hamcrest-core' '1.3')
)
$sources = @(
    'app/src/main/java/StarBase/Android/Forum/data/Live.kt'
    'app/src/main/java/StarBase/Android/Forum/data/ReaderNavigation.kt'
    'app/src/main/java/StarBase/Android/Forum/speech/SpeechText.kt'
    'app/src/main/java/StarBase/Android/Forum/speech/SpeechQueue.kt'
    'app/src/test/java/StarBase/Android/Forum/speech/SpeechTextTest.kt'
    'app/src/test/java/StarBase/Android/Forum/speech/SpeechQueueTest.kt'
)
$extraArgs = @()
if ($IncludeAndroid) {
    $sources += @(
        'app/src/main/java/StarBase/Android/Forum/speech/SpeechController.kt'
        'app/src/main/java/StarBase/Android/Forum/speech/TopicSpeechService.kt'
        'app/src/main/java/StarBase/Android/Forum/speech/SpeechControls.kt'
        'app/src/androidTest/java/StarBase/Android/Forum/speech/SpeechServiceSmokeTest.kt'
    )
    $dependencies += Get-ChildItem (Join-Path $cacheRoot '8.14.5/transforms') -Filter classes.jar -Recurse |
        Where-Object { $_.FullName -match '[/\\]jars[/\\]classes\.jar$' } |
        Select-Object -ExpandProperty FullName
    $dependencies += Get-Jar 'androidx.annotation' 'annotation-jvm' '1.9.1'
    $dependencies += Get-Jar 'androidx.lifecycle' 'lifecycle-common-jvm' '2.9.3'
    $dependencies += Join-Path $projectRoot '_tools/sdk/platforms/android-36/android.jar'
    $extraArgs += '-Xplugin=' + (Get-Jar 'org.jetbrains.kotlin' 'kotlin-compose-compiler-plugin-embeddable' '2.3.21')
}
$classpath = $dependencies -join ';'
$classes = Join-Path $taskOutput 'classes'
$compilerArgs = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-module-name', 'speech_offline', '-classpath', $classpath, '-d', $classes)
$compilerArgs += $extraArgs
$compilerArgs += $sources | ForEach-Object { Join-Path $projectRoot $_ }
$argumentFile = Join-Path $taskOutput 'compiler.args'
$encodedArgs = $compilerArgs | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }
[IO.File]::WriteAllLines($argumentFile, $encodedArgs, [Text.UTF8Encoding]::new($false))
& $speechJava '-Xmx1024m' '-cp' $compilerClasspath 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw "Speech Kotlin compilation failed: $LASTEXITCODE" }

$runtimeClasspath = @($classes, $classpath) -join ';'
$runtimeArgs = @('-cp', $runtimeClasspath, 'org.junit.runner.JUnitCore', 'StarBase.Android.Forum.speech.SpeechTextTest', 'StarBase.Android.Forum.speech.SpeechQueueTest')
$runtimeArgumentFile = Join-Path $taskOutput 'junit.args'
[IO.File]::WriteAllLines($runtimeArgumentFile, ($runtimeArgs | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }), [Text.UTF8Encoding]::new($false))
& $speechJava "@$runtimeArgumentFile"
if ($LASTEXITCODE -ne 0) { throw "Speech tests failed: $LASTEXITCODE" }
Write-Output "Speech offline results: $taskOutput"
