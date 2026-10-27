# The Kaos image: the shaded jar of kaos-server on a Java 21 runtime.
#
# The jar is built first, outside of Docker:
#   ./mvnw clean package -DskipTests -DskipITs
#   docker build -t kaos .
#
# The release workflow (.github/workflows/release.yml) builds and pushes
# this image for each release tag.
FROM eclipse-temurin:21-jre

RUN useradd --system --no-create-home --shell /usr/sbin/nologin kaos

# The pattern matches the runnable jar only: the shade plugin keeps the
# unshaded one as original-kaos-server-*.jar.
COPY kaos-server/target/kaos-server-*.jar /opt/kaos/kaos.jar

USER kaos
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/opt/kaos/kaos.jar"]
