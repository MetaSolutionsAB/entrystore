mvn dependency:list -Dsort=true | grep INFO | grep : | grep '   ' | awk '{print $2}' | cut -f1-4 -d: | sort | uniq | cut -f1-3 -d: | uniq -c | grep -v '^ *1 '

echo -e "\nInvestigate dependency with: mvn dependency:tree -Dincludes=groupId:artifactId"
