#!/bin/bash

#JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn $1 $2 -Dmaven.test.skip=true install
mvn $1 $2 -Dmaven.test.skip=true install
