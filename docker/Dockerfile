FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.authors="devops@metasolutions.se"
LABEL se.metasolutions.service="entrystore"

RUN apk --no-cache upgrade && apk --no-cache add wget

RUN addgroup -g 2000 entrystore &&\
    adduser -G entrystore -D -H -g "" -h /srv/entrystore -u 2000 entrystore

RUN mkdir -p \
      /srv/entrystore/data/store \
      /srv/entrystore/data/store-prov \
      /srv/entrystore/data/files \
      /srv/entrystore/data/solr \
      /srv/entrystore/data/backup &&\
    chown -R entrystore:entrystore /srv/entrystore

ARG ENTRYSTORE_VERSION
COPY --chown=entrystore:entrystore modules/rest/spring-boot/target/entrystore-rest-spring-boot-${ENTRYSTORE_VERSION}-exec.jar /srv/entrystore/entrystore.jar
COPY --chown=entrystore:entrystore docker/docker-entrypoint.sh /srv/entrystore/docker-entrypoint.sh

EXPOSE 8080
WORKDIR /srv/entrystore
USER entrystore

ENTRYPOINT ["/srv/entrystore/docker-entrypoint.sh"]
