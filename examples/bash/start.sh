#! /bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

# Connects a xaas to a rabbit broker running on localhost
# Mounting the filters catalog
docker run \
  -e AMQP_HOST=localhost \
  -e AMQP_USER=${AMQP_USER:-guest} \
  -e AMQP_PASSWORD=${AMQP_PASSWORD:-guest} \
  -e AMQP_QUEUE=xslt.transform \
  -e FILTER_PATH=/filters \
  -v $DIR/filters:/filters \
  --network "host" \
  lenkan/xaas
