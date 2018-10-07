FROM gradle as builder
WORKDIR /xaas

USER root
RUN chown -R gradle /xaas
USER gradle

# TODO: Should be able to resolve dependencies here
COPY build.gradle .
RUN gradle tasks

COPY src ./src
RUN gradle build installDist

FROM openjdk:latest
WORKDIR /xaas
COPY --from=builder /xaas/build /xaas/build

RUN mkdir /xaas/xslt
ENV XSLT_ROOT=/xaas/xslt

CMD ["./build/install/xaas/bin/xaas"]
