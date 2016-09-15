FROM maven:3.2-jdk-7-onbuild

ENV VERSION 0.1

WORKDIR /usr/src/app

#VOLUME ["/usr/src/walletd"]
EXPOSE 8332 8333 18332 18333
CMD ["java", "-jar", "target/bittiraha-walletd-${VERSION}-SNAPSHOT-shaded.jar"]
