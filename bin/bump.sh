#!/bin/bash

# This script bumps version in the various files that reference the current TinkerPop version files (e.g. pom.xml)
# in preparation for release. Usage:
#
# bin/bump.sh "version"

VERSION=$1

# update pom.xml
for pom in $(find . -name pom.xml); do
  cat $pom | grep -n -A2 -B2 '<groupId>com.tinkerpop</groupId>' \
           | grep -A2 -B2 '<artifactId>tinkerpop</artifactId>'  \
           | grep '<version>' | cut -f1 -d '-' | xargs -n1 -I{} sed -i -e "{}s@>.*<@>${VERSION}<@" $pom
done

# update YAML configuration
sed 's/\[com.tinkerpop, neo4j-gremlin, ".*"\]/\[com.tinkerpop, neo4j-gremlin, "'"${VERSION}"'"\]/' gremlin-server/conf/gremlin-server-neo4j.yaml > gremlin-server/conf/gremlin-server-neo4j1.yaml && mv gremlin-server/conf/gremlin-server-neo4j1.yaml gremlin-server/conf/gremlin-server-neo4j.yaml

# update README
sed 's/\(http:\/\/tinkerpop.com\/.*docs\/\)[A-Za-z0-9.-]*\/\(.*\)/\1'"${VERSION}"'\/\2/' README.asciidoc > README1.asciidoc && mv README1.asciidoc README.asciidoc