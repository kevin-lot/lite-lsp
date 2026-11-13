# syntax=docker/dockerfile:1
ARG RUST_VERSION=1.85

FROM rust:${RUST_VERSION}-slim AS build
WORKDIR /src
COPY backend/rust .
RUN cargo build --release

FROM debian:bookworm-slim
ENV APP_HOME=/app
WORKDIR $APP_HOME
COPY --from=build /src/target/release/playground $APP_HOME/
CMD ["./playground"]
