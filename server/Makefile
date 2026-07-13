# Build a static, dependency-free Linux binary for the DayView sync endpoint.
# Override the target arch with e.g. `make build GOARCH=arm64`.
BINARY  ?= sync-endpoint
GOOS    ?= linux
GOARCH  ?= amd64

# CGO_ENABLED=0 makes net/http pure-Go, so the binary has no libc dependency and
# runs on any Linux of $(GOARCH) regardless of glibc version. -s -w strip debug info.
build:
	CGO_ENABLED=0 GOOS=$(GOOS) GOARCH=$(GOARCH) go build -trimpath -ldflags='-s -w' -o $(BINARY) .

run:
	go run .

test:
	go vet ./...

clean:
	rm -f $(BINARY)

.PHONY: build run test clean
