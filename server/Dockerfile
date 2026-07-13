# Multi-stage: compile a static binary, then ship it in an empty (scratch) image.
# The result is a few MB with no OS layer and no runtime dependencies. Synology
# Container Manager builds this natively for the NAS's own architecture (amd64),
# so no cross-compilation flags are needed there.
FROM golang:1.25-alpine AS build
ARG TARGETARCH=amd64
WORKDIR /src
COPY go.mod .
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -trimpath -ldflags='-s -w' -o /sync-endpoint .

FROM scratch
COPY --from=build /sync-endpoint /sync-endpoint
# The store lives on a mounted volume so it survives container/image rebuilds.
ENV SYNC_STORE=/data/store.json
ENV SYNC_PORT=8787
EXPOSE 8787
VOLUME ["/data"]
ENTRYPOINT ["/sync-endpoint"]
