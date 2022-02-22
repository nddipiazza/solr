set -e

rm -rf build/test-dist
mkdir -p build/test-dist
cp -vr solr/packaging/build/solr-10.0.0-SNAPSHOT/* build/test-dist
#rm -rf build/test-dist/example
#cp -vr /home/ndipiazza/Downloads/example build/test-dist
#rm -f /home/ndipiazza/lucidworks/solr/build/test-dist/example/cloud/node1/logs/*