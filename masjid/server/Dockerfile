FROM node:22-slim
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY . .
ENV PORT=3000 DATA_DIR=/data TZ=Asia/Dhaka
VOLUME /data
EXPOSE 3000
CMD ["node","--no-warnings","index.js"]
