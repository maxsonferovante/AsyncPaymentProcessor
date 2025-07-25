# Build GraalVM Native
FROM ghcr.io/graalvm/graalvm-community:24-ol9 AS builder

RUN microdnf update -y && \
    microdnf install -y gcc glibc-devel zlib-devel libstdc++-static && \
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
FROM redhat/ubi9-minimal:latest AS runtime

RUN microdnf update -y && \
    microdnf install -y glibc libgcc libstdc++ && \
    microdnf clean all && \
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

