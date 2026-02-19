FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.authors="devops@metasolutions.se"
LABEL se.metasolutions.service="entrystore"

RUN apk update && apk upgrade && apk --no-cache add wget && rm -rf /var/cache/apk/*

RUN mkdir -p \
      /srv/entrystore/data/store \
      /srv/entrystore/data/store-prov \
      /srv/entrystore/data/files \
      /srv/entrystore/data/solr \
      /srv/entrystore/data/backup

ARG ENTRYSTORE_VERSION
COPY modules/rest-standalone/spring-boot/target/entrystore-rest-standalone-spring-boot-${ENTRYSTORE_VERSION}-exec.jar /srv/entrystore/entrystore.jar
COPY docker/docker-entrypoint.sh /srv/entrystore/docker-entrypoint.sh

EXPOSE 8080
WORKDIR /srv/entrystore

ENTRYPOINT ["/srv/entrystore/docker-entrypoint.sh"]
