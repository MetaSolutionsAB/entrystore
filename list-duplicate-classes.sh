#!/bin/bash

./mvnw -Dduplicate-finder.checkTestClasspath=false org.basepom.maven:duplicate-finder-maven-plugin:2.0.1:check
