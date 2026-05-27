# Dependencies and their licenses

Run the following command to build the license documentation for all built modules:

```
./mvnw project-info-reports:dependencies
```

The results can found in the modules' target directories (`target/site/dependencies.html`), e.g. at `modules/rest/spring-boot/target/site/dependencies.html`.
