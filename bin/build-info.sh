#!/usr/bin/env bash
version=$(printf 'VER\t${project.version}' | ./mvnw help:evaluate | grep '^VER' | cut -f2)
clean_version=$(echo $version | sed 's/-SNAPSHOT//')
commitId="$(git log --format="%H" -n 1)"
buildNumber="$(git rev-list --all --count)"
buildId="${commitId:0:7}"
echo -e "server.port=9090\nversion=${clean_version}\npluginJvmArgs=-Dfile.encoding=UTF-8 -Xms4m -Xmx32m\nbuildId=${buildId}\nbuildTime=${Date}\nbuildNumber=${buildNumber}" > src/main/resources/conf.properties
