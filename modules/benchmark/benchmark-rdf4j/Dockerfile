FROM openjdk:23-jdk-bullseye
MAINTAINER entryscape.com
COPY benchmark-rdf4j-0.1.0-SNAPSHOT.jar app.jar
COPY libs libs
ENTRYPOINT ["java","-jar","/app.jar"]