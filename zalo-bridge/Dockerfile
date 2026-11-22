# Dịch vụ cầu nối Zalo (D16). Chỉ chạy khi bật profile Docker "zalo-auto"; không công bố cổng ra ngoài.
FROM node:22-bookworm-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# Cài đúng phiên bản trong lockfile (kiểm tra toàn vẹn); không chạy script cài đặt của gói.
RUN npm ci --omit=dev --ignore-scripts

FROM node:22-bookworm-slim
ENV NODE_ENV=production
WORKDIR /app
COPY --from=deps /app/node_modules node_modules
COPY package.json ./
COPY src src
# Chạy bằng người dùng thường (node, uid 1000); thư mục dữ liệu chứa phiên mã hóa nên chỉ người này đọc được.
RUN mkdir /data && chown node:node /data && chmod 700 /data
USER node
VOLUME /data
ENV BRIDGE_DATA_DIR=/data
EXPOSE 3100
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:3100/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "src/main.mjs"]
