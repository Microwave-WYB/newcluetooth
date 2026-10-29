# androzoo-sync: loads the AndroZoo APK list into androzoo.apks and keeps it current.
FROM node:24.22.2-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY src ./src
ENV NODE_ENV=production
# The newest downloaded list (one ~3.5 GB file) and, during a download, its parts.
VOLUME ["/var/lib/androzoo-sync"]
CMD ["node", "src/schedule.mjs"]
