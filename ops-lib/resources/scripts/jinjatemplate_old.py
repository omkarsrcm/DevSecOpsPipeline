import json
import os
import yaml
import sys

# Below function is used to open and load json file
with open(sys.argv[1], 'r') as f:
  data = json.load(f)
#print(data)

from jinja2 import Environment, FileSystemLoader
env = Environment(loader=FileSystemLoader(sys.argv[2]))  #Need to be input parameter
#print(env)
template = env.get_template(sys.argv[3]) #Need to be input parameter
output_from_parsed_template = template.render(data)
#print(output_from_parsed_template)

# to save the results
with open(sys.argv[4], "w") as fh:
    fh.write(output_from_parsed_template)
