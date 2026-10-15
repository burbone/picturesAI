FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY api-gateway/src ./api-gateway/src
RUN apt-get update && apt-get install -y maven && mvn -B -pl api-gateway -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]