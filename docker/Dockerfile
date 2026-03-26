FROM eclipse-temurin:25-jre-alpine

WORKDIR /srv/entrystore

LABEL org.opencontainers.image.authors="devops@metasolutions.se"
LABEL se.metasolutions.service="entrystore"

RUN apk --no-cache upgrade && apk --no-cache add wget

RUN addgroup -g 2000 entrystore &&\
    adduser -G entrystore -D -H -g "" -h /srv/entrystore -u 2000 entrystore

RUN mkdir -p \
      data/store \
      data/store-prov \
      data/files \
      data/solr \
      data/backup &&\
    chown -R entrystore:entrystore .

ARG ENTRYSTORE_VERSION
COPY --chown=entrystore:entrystore docker/docker-entrypoint.sh docker-entrypoint.sh
COPY --chown=entrystore:entrystore modules/rest/spring-boot/target/entrystore-rest-spring-boot-${ENTRYSTORE_VERSION}-exec.jar entrystore.jar

EXPOSE 8080
USER entrystore

ENTRYPOINT ["./docker-entrypoint.sh"]
