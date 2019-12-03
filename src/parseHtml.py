import os
import math
import ast
import astpretty
import bs4


def hasChild(node):
    try:
        node.children
        return True
    except:
        return False


def main():
    f = open('b.html')
    soup = bs4.BeautifulSoup(f.read(), "lxml")
    f.close()
    bfs = [soup]
    begin = ""
    while bfs:
        for i in range(len(bfs)):
            node = bfs.pop(0)
            print(begin + str(node.name))
            for i in node.children:
                if hasChild(i):
                    bfs.append(i)
        begin += "  "


if __name__ == "__main__":
    main()
