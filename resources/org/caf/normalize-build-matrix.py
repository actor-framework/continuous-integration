#!/usr/bin/env python

import sys, json

from functools import reduce

inputFile = sys.argv[1]
outputFile = sys.argv[2]

with open(inputFile, 'r') as f:
    data = f.read()

def copy_without(xs, key):
    ys = xs.copy()
    ys.pop(key)
    return ys

def merge_filtered(result, xs, pred):
    for key, val in xs.items():
        if pred(key):
            result[key] = val
    return result

def make_pipeline(*functions):
    return reduce(lambda f, g: lambda x: g(f(x)), functions)

def filter_step(f):
    return lambda xs: filter(f, xs)

def map_step(f):
    return lambda xs: map(f, xs)

def reduce_step(f):
    return lambda xs: reduce(f, xs)

def lift_builds(os, settings):
    res = map(lambda build: [os, build, copy_without(settings, 'builds')],
              settings['builds'])
    return list(res)

def normalize(what, settings, buildType, defaults):
    key1 = 'extraBuild{}'.format(what.capitalize())
    key2 = 'extra{}Build{}'.format(buildType.capitalize(), what.capitalize())
    res1 = settings[key1] if key1 in settings else []
    res2 = settings[key2] if key2 in settings else []
    return merge_filtered({ what: defaults + res1 + res2 },
                          settings,
                          lambda key: not key.endswith(what.capitalize()))

def normalize_entry(os, build_type, settings, what):
    key = 'build{}'.format(what.capitalize())
    defaults = settings[key] if key in settings else []
    return [os, build_type, normalize(what, settings, build_type, defaults)]

def add_tags_if_missing(os, build_type, settings):
    res = settings
    if not 'tags' in res:
        res['tags'] = []
    return [os, build_type, res]

def call(f, args):
    return f(*args)


config = json.loads(data)
buildMatrix = config['buildMatrix']

pipeline = make_pipeline( \
    map_step(lambda entry: lift_builds(*entry)),
    reduce_step(lambda x, y: x + y),
    map_step(lambda entry: call(normalize_entry, entry + ['flags'])),
    map_step(lambda entry: call(normalize_entry, entry + ['env'])),
    map_step(lambda entry: add_tags_if_missing(*entry)))

normalizedBuildMatrix = list(pipeline(buildMatrix))

print(normalizedBuildMatrix)

with open(outputFile, 'w') as f:
    json.dump(normalizedBuildMatrix, f, indent=2)
