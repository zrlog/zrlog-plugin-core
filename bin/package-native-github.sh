#!/usr/bin/env bash
basePath=${1}
packageMavenArgs=("${@:2}")
agentMavenArgs=()
for arg in "${packageMavenArgs[@]}"; do
  if [[ "${arg}" == "-Dmysql-scope=provided" ]]; then
    continue
  fi
  agentMavenArgs+=("${arg}")
done
mkdir -p "${basePath}"
echo "real target folder ${basePath}"

java -version
sh bin/build-info.sh
./mvnw "${packageMavenArgs[@]}" -U -PnodeBuild clean package
./mvnw "${agentMavenArgs[@]}" -Pnative -Dagent exec:exec@java-agent -U
./mvnw "${packageMavenArgs[@]}" -Pnative -DskipNativeTests package
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
