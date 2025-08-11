# Build GraalVM Native
FROM ghcr.io/graalvm/graalvm-community:latest AS builder

RUN microdnf install -y gcc gcc-c++ make zlib-devel && \
    microdnf clean all

WORKDIR /app

COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon

COPY src/ src/

RUN ./gradlew nativeCompile \
    --no-daemon \
    --console=plain \
    -Dorg.gradle.jvmargs="-Xmx3g" \
    -Pspring.aot.jvmArgs="-Xmx1g" \
    -Pspring.native.gradle.build-args="-O3,--gc=G1,--enable-preview,--strict-image-heap,--enable-native-access=ALL-UNNAMED"

# Runtime
FROM ubuntu:22.04 AS runtime

RUN apt-get update && \
    apt-get install -y ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    adduser --system --no-create-home appuser

WORKDIR /app

COPY --from=builder /app/build/native/nativeCompile/AsyncPaymentProcessor ./app
RUN chmod +x ./app

USER appuser

ENV MALLOC_ARENA_MAX=2
ENV GC_MAX_HEAP_FREE_RATIO=10

EXPOSE 8089

HEALTHCHECK --interval=5s --timeout=2s --start-period=5s --retries=3 \
      CMD test -e /proc/self || exit 1

ENTRYPOINT ["./app"]