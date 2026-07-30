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
if [ -f "target/${binName}.exe" ];
then
  echo "window"
  sourceFile="target/${binName}.exe"
  targetFile="${basePath}/${binName}-Windows-$(uname -m).exe"
  mv ${sourceFile} ${targetFile}
  exit 0;
fi
if [[ "$(uname -s)" == "Linux" ]];
then
  echo "Linux"
  sourceFile="target/${binName}"
  targetFile="${basePath}/${binName}-$(uname -s)-$(dpkg --print-architecture).bin"
  mv ${sourceFile} ${targetFile}
else
  echo "MacOS"
  sourceFile="target/${binName}"
  targetFile="${basePath}/${binName}-$(uname -s)-$(uname -m).bin"
  mv ${sourceFile} ${targetFile}
fi
