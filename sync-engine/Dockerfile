FROM alpine:3.22
RUN apk add --no-cache ca-certificates
COPY --chmod=755 vervetwodo-server /usr/local/bin/vervetwodo-server
USER 1000:1000
ENV DATA_DIR=/data LISTEN_ADDR=:8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s CMD ["vervetwodo-server", "healthcheck"]
ENTRYPOINT ["vervetwodo-server"]
