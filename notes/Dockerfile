FROM node:24-bookworm-slim AS assets
WORKDIR /app
COPY package.json package-lock.json vendor.mjs ./
RUN npm ci --omit=dev --ignore-scripts
COPY web ./web
RUN npm run vendor

FROM node:24-bookworm-slim
WORKDIR /app
COPY --from=assets /app/web ./web
COPY server.mjs lan.mjs ./
RUN mkdir /data && chown node:node /data
USER node
ENV NODE_ENV=production HOST=0.0.0.0 PORT=4319 ECHO_DATA=/data
EXPOSE 4319
CMD ["node","server.mjs"]
