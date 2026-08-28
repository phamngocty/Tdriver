FROM node:18-alpine

WORKDIR /app

COPY package.json ./
COPY server.js ./

# Create data directory
RUN mkdir -p /app/data

ENV PORT=8088
ENV DATA_DIR=/app/data

EXPOSE 8088

CMD ["node", "server.js"]
