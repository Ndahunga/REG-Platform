# Build stage
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci

COPY . .
ARG VITE_API_BASE_URL=/api/v1
ARG VITE_APP_NAME="OuWealth Community"
ARG VITE_APP_ENV=production
ARG VITE_ENABLE_PWA=false
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_APP_NAME=$VITE_APP_NAME \
    VITE_APP_ENV=$VITE_APP_ENV \
    VITE_ENABLE_PWA=$VITE_ENABLE_PWA

RUN npm run build

# Serve stage
FROM nginx:1.27-alpine
# nginx.conf includes X-Content-Type-Options / X-Frame-Options / Referrer-Policy /
# Permissions-Policy. Enable HSTS on the HTTPS reverse proxy in front of this image.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
