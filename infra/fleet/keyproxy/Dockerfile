# keyproxy: Node built-ins only, so there is no npm install step.
FROM node:24.22.2-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
# Compose sets `user:` to the host account that owns the secret files (mode 600); /var/lib/keyproxy
# is a volume for the daily counters and the request log.
RUN mkdir -p /var/lib/keyproxy && chmod 1777 /var/lib/keyproxy
ENV NODE_ENV=production
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
CMD ["node", "src/server.mjs"]
