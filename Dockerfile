FROM openjdk:latest as builder
WORKDIR /xaas

# Install gradle wrapper and lock dependencies
COPY build.gradle gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies

# Copy source and build
COPY src ./src
RUN ./gradlew build installDist

FROM openjdk:latest
WORKDIR /xaas
COPY --from=builder /xaas/build /xaas/build

RUN mkdir /xaas/xslt
ENV XSLT_ROOT=/xaas/xslt

CMD ["./build/install/xaas/bin/xaas"]
