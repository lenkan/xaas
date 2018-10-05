FROM openjdk:latest

RUN apt-get update
RUN curl -sL https://deb.nodesource.com/setup_8.x | bash -
RUN apt-get install -y nodejs

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm run setup && npm install --production

COPY . .

CMD ["npm", "start"]
