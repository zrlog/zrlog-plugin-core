#!/usr/bin/env bash
basePath=${1}
packageMavenArgs=("${@:2}")
nativeMavenArgs=("${packageMavenArgs[@]}" "-Dsqlite-scope=provided")
nativeProfiles="native"
if [[ "${ZRLOG_PACKAGE_TYPE:-native}" != "faas" ]]; then
  nativeProfiles="${nativeProfiles},native-sqlite"
fi
agentMavenArgs=()
for arg in "${nativeMavenArgs[@]}"; do
  if [[ "${arg}" == "-Dmysql-scope=provided" ]]; then
    continue
  fi
  agentMavenArgs+=("${arg}")
done
mkdir -p "${basePath}"
echo "real target folder ${basePath}"

java -version
bash -e bin/build-info.sh
./mvnw "${nativeMavenArgs[@]}" -U -PnodeBuild clean package
./mvnw "${agentMavenArgs[@]}" -P"${nativeProfiles}" -Dagent exec:exec@java-agent -U
./mvnw "${nativeMavenArgs[@]}" -P"${nativeProfiles}" -DskipNativeTests package
binName="plugin-core"
targetFile=""
sourceFile=""
artifactArchitecture=""
if [ -f "target/${binName}.exe" ];
then
  echo "window"
  sourceFile="target/${binName}.exe"
  targetFile="${basePath}/${binName}-Windows-$(uname -m).exe"
  artifactArchitecture="Windows-$(uname -m)"
elif [[ "$(uname -s)" == "Linux" ]];
then
  echo "Linux"
  sourceFile="target/${binName}"
  artifactArchitecture="$(uname -s)-$(dpkg --print-architecture)"
  targetFile="${basePath}/${binName}-${artifactArchitecture}.bin"
else
  echo "MacOS"
  sourceFile="target/${binName}"
  artifactArchitecture="$(uname -s)-$(uname -m)"
  targetFile="${basePath}/${binName}-${artifactArchitecture}.bin"
fi

mv "${sourceFile}" "${targetFile}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  artifactVersion=$(sed -n 's/^version=//p' src/main/resources/conf.properties)
  if [[ -z "${artifactVersion}" ]]; then
    echo "Unable to resolve plugin-core version from conf.properties" >&2
    exit 1
  fi
  {
    echo "artifact_file=${targetFile}"
    echo "artifact_name=${binName}"
    echo "artifact_version=${artifactVersion}"
    echo "artifact_architecture=${artifactArchitecture}"
  } >> "${GITHUB_OUTPUT}"
fi
