# ---- Build ----
FROM node:20-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Doc .env.production -> VITE_API_BASE_URL=/api
RUN npm run build

# ---- Run ----
FROM nginx:1.27-alpine
ENV BACKEND_URL=http://backend:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
