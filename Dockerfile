FROM neo4j:5.7.0-community
MAINTAINER Jinghe Song <songjh@buaa.edu.cn>

RUN echo Asia/Shanghai > /etc/timezone

# install necessary software for neo4j.
RUN apt-get update && apt-get install -y --no-install-recommends \
  wget curl zip unzip git miller \
  && rm -rf /var/lib/apt/lists/*

# install maven
ENV MAVEN_VERSION 3.9.10
RUN wget -nv "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" \
  && tar xzf "apache-maven-$MAVEN_VERSION-bin.tar.gz" -C /usr/share \
  && mv "/usr/share/apache-maven-$MAVEN_VERSION" /usr/share/maven \
  && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn \
  && rm "apache-maven-$MAVEN_VERSION-bin.tar.gz"

# COPY maven-settings.xml /usr/share/maven/conf/settings.xml

ENV MAVEN_HOME /usr/share/maven

RUN curl -s "https://get.sdkman.io" | bash \
 && source "$HOME/.sdkman/bin/sdkman-init.sh" \
 && sdk install java 17.0.7-tem \
 && sdk install gradle 8.5 

WORKDIR /db/bin/aion

COPY . .

WORKDIR /db/bin/aion/community/temporal-graph
RUN git clone https://github.com/neo4j/graph-data-science.git \
 && cd graph-data-science \
 && git checkout 2.4.0-alpha06 \
 && git apply ../temporal.patch

ENV GRADLE_OPTS "--add-exports jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED"

RUN ./gradlew :open-packaging:shadowCopy -Pneo4jVersion=5.7.0 -x javadoc
RUN ./gradlew publishToMavenLocal -x javadoc

WORKDIR /db/bin/aion/community
RUN mvn -B clean install -DskipTests -Dspotless.check.skip -Dlicense.skip -Denforcer.skip -T1C

# ENTRYPOINT ["/db/bin/aion/docker-entrypoint.sh"]
