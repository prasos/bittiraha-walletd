FROM webratio/ant:1.9.4

ENV VERSION 0.1

RUN apt-get update && apt-get install -y moreutils

ADD . /usr/src/walletd

RUN cd /usr/src/walletd && sh build.sh

RUN mkdir /usr/src/data
WORKDIR /usr/src/data
VOLUME ["/usr/src/data"]

EXPOSE 8332 8333 18332 18333
RUN cp /usr/src/walletd/*net.conf /usr/src/data

RUN printf "cp /usr/src/walletd/*net.conf /usr/src/data/\n" >> /usr/src/walletd/docker-entry.sh && \
    printf "java -cp /usr/src/walletd/build/jar/Walletd.jar:/usr/src/walletd/lib/* fi.bittiraha.walletd.Main 2>&1" >> /usr/src/walletd/docker-entry.sh && \
    printf " | ts | tee /usr/src/data/debug.log" >> /usr/src/walletd/docker-entry.sh

CMD [ "sh", "/usr/src/walletd/docker-entry.sh" ]
