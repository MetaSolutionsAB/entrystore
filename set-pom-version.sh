#!/bin/bash

mvn versions:set -DnewVersion=$1
echo $1 > VERSION.txt
cp VERSION.txt modules/rest/src/main/resources/
