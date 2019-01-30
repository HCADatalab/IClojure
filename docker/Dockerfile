FROM openjdk:jdk-slim
RUN apt update
RUN apt install -y python3 python3-pip
RUN pip3 install jupyterlab
RUN apt install -y git curl
RUN curl -sL https://deb.nodesource.com/setup_10.x | bash -
RUN apt install -y nodejs
RUN curl -o /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && chmod +x /usr/local/bin/lein
ENV LEIN_ROOT 1
RUN lein self-install
RUN mkdir /notebooks
RUN adduser --gecos 'build user' --disabled-password iclj
RUN mkdir -p /usr/local/share/jupyter/lab/extensions 
RUN chown -R iclj /usr/local/share/jupyter/lab/
RUN chown -R iclj /notebooks
RUN update-alternatives --install /usr/bin/python python /usr/bin/python2.7 1
RUN update-alternatives --install /usr/bin/python python /usr/bin/python3.5 2
USER iclj
WORKDIR /home/iclj
RUN git clone https://github.com/HCADatalab/IClojure.git
WORKDIR /home/iclj/IClojure
RUN make && make install
RUN jupyter labextension install iclojure_extension
RUN rm -rf /home/iclj/IClojure

WORKDIR /notebooks
VOLUME /notebooks
EXPOSE 8888
ENV JAVA_TOOL_OPTIONS -Dfile.encoding=UTF8
CMD ["/usr/local/bin/jupyter-lab", "--ip=0.0.0.0"]
