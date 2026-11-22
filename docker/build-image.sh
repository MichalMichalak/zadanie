#!/usr/bin/env bash
#
# Builds the coupon-service image.
#
# The Docker build context is a temporary directory holding only the Dockerfile and the boot jar,
# so the repository itself (sources, tests, dev-env/.env, IDE directories, scratch files) is never
# sent to the Docker daemon. Nothing needs to be excluded, which is why there is no .dockerignore.
#
# Usage:
#   ./docker/build-image.sh                  # build the jar, then the image
#   ./docker/build-image.sh --skip-build     # package the existing jar
#   ./docker/build-image.sh --tag app:1.2.3  # custom tag (default: coupon-service)
#   ./docker/build-image.sh --help
#
# Environment:
#   JAR    boot jar to package (default: newest app/build/libs/*-SNAPSHOT.jar)
#   IMAGE  default image tag (overridden by --tag)

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

image=${IMAGE:-coupon-service}
skip_build=false

usage() {
    cat <<'EOF'
usage: ./docker/build-image.sh [--skip-build] [--tag IMAGE]

  --skip-build   use the boot jar already in app/build/libs instead of rebuilding it
  --tag IMAGE    image tag (default: $IMAGE when set, otherwise coupon-service)

environment:
  JAR            explicit path to the boot jar
  IMAGE          default image tag
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)
            skip_build=true
            shift
            ;;
        -t|--tag)
            image=${2:?--tag needs a value}
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument '$1'" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ "$skip_build" == false ]]; then
    echo "building boot jar..."
    (cd "$repo_root" && ./gradlew :app:bootJar)
fi

# bootJar writes app-<version>.jar next to a jar-<version>-plain.jar; the glob excludes the latter.
if [[ -n "${JAR:-}" ]]; then
    jar=$JAR
else
    shopt -s nullglob
    jars=("$repo_root"/app/build/libs/*-SNAPSHOT.jar)
    shopt -u nullglob
    if (( ${#jars[@]} == 0 )); then
        echo "error: no boot jar in app/build/libs; run ./gradlew :app:bootJar or drop --skip-build" >&2
        exit 1
    fi
    jar=${jars[$((${#jars[@]} - 1))]}
fi

if [[ ! -f "$jar" ]]; then
    echo "error: boot jar '$jar' not found" >&2
    exit 1
fi

context=$(mktemp -d "${TMPDIR:-/tmp}/coupon-service-image.XXXXXX")
trap 'rm -rf "$context"' EXIT

cp "$script_dir/Dockerfile" "$context/Dockerfile"
cp "$jar" "$context/application.jar"

echo "building image '$image' from $(basename "$jar")..."
docker build --file "$context/Dockerfile" --tag "$image" "$context"
