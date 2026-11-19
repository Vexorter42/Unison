FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /unison-api .

FROM alpine:latest
RUN addgroup -g 1000 unison && adduser -D -u 1000 -G unison unison \
    && mkdir -p /data && chown unison:unison /data
COPY --from=build /unison-api /usr/local/bin/unison-api
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/unison-api"]
