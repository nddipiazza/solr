set -e
./gradlew :solr:core:clean :solr:core:assemble
cp -vf /home/ndipiazza/lucidworks/solr/solr/core/build/libs/solr-core-10.0.0-SNAPSHOT.jar /home/ndipiazza/lucidworks/solr/build/test-dist/server/solr-webapp/webapp/WEB-INF/lib/solr-core-10.0.0-SNAPSHOT.jar