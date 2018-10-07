FROM openjdk:latest as builder

WORKDIR /xaas

# A little naive, should split into steps to use cache
COPY . .
RUN ./gradlew clean build


# Probably not correct? Should run some output
CMD ["./gradlew", "run"]
