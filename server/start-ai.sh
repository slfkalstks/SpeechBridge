#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$project_dir/.runtime/models"
export OLLAMA_HOST=127.0.0.1:11435
export OLLAMA_MODELS="$project_dir/.runtime/models"
export OLLAMA_NO_CLOUD=1
export OLLAMA_NUM_PARALLEL=1
export OLLAMA_MAX_LOADED_MODELS=1
export OLLAMA_MAX_QUEUE=3
export OLLAMA_CONTEXT_LENGTH=4096
export OLLAMA_DEBUG_LOG_REQUESTS=false
exec ollama serve
