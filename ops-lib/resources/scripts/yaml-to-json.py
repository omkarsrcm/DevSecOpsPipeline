import json
import sys
import yaml

#Read the Yaml via parameter
with open(sys.argv[1], 'r') as file:
    configuration = yaml.safe_load(file)

#Write to Json file
with open(sys.argv[2], 'w') as json_file:
    json.dump(configuration, json_file,indent=2)
    
#Below is used to print the json file.
#output = json.dumps(json.load(open(sys.argv[2])), indent=2)
#print(output)