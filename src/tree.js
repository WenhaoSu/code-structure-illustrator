let treeData;
let fileName = 'hugeTree.json';
let test = [];

function asyncGetFile(url) {
    console.log("Getting text file");
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("GET", url);
        xhr.onload = () => resolve(xhr.responseText);
        xhr.onerror = () => reject(xhr.statusText);
        xhr.send();
        console.log("Made promise");
    });
}

function generateTree() {
    // ************** Generate the tree diagram	 *****************
    let margin = {top: 20, right: 80, bottom: 20, left: 80},
        width = 3000 - margin.right - margin.left,
        height = 500 - margin.top - margin.bottom;

    let i = 0,
        duration = 750,
        root;

    let tree = d3.layout.tree().size([height, width]);

    let diagonal = d3.svg.diagonal()
        .projection(function (d) {
            return [d.y, d.x];
        });
    d3.select("body").select("#graph").select("svg").remove();

    let svg = d3.select("body").select("#graph").append("svg")
        .attr("width", width + margin.right + margin.left)
        .attr("height", height + margin.top + margin.bottom)
        .append("g")
        .attr("transform", "translate(" + margin.left + "," + margin.top + ")");

    root = treeData;
    root.x0 = height / 2;
    root.y0 = 0;

    update(root);

    d3.select(self.frameElement).style("height", "800px");

    function update(source) {

        // Dynamically change the height of tree.
        let newHeight = Math.max(tree.nodes(root).reverse().length * 20, height);

        tree = d3.layout.tree().size([newHeight, width]);

        d3.select("svg")
            .attr("width", width + margin.right + margin.left)
            .attr("height", newHeight + margin.top + margin.bottom);

        // Compute the new tree layout.
        let nodes = tree.nodes(root).reverse(),
            links = tree.links(nodes);

        // Normalize for fixed-depth.
        nodes.forEach(function (d) {
            d.y = d.depth * 90;
        });

        // Update the nodes…
        let node = svg.selectAll("g.node")
            .data(nodes, function (d) {
                return d.id || (d.id = ++i);
            });

        // Enter any new nodes at the parent's previous position.
        let nodeEnter = node.enter().append("g")
            .attr("class", "node")
            .attr("transform", function (d) {
                return "translate(" + source.y0 + "," + source.x0 + ")";
            })
            .on("click", click);

        nodeEnter.append("circle")
            .attr("r", 1e-6)
            .style("fill", function (d) {
                return d._children ? "lightsteelblue" : "#fff";
            });

        nodeEnter.append("text")
            .attr("x", function (d) {
                return d.children || d._children ? -13 : 13;
            })
            .attr("dy", ".35em")
            .attr("text-anchor", function (d) {
                return d.children || d._children ? "end" : "start";
            })
            .text(function (d) {
                return d.name;
            })
            .style("fill-opacity", 1e-6);

        nodeEnter.append("text")
            .attr('class', 'info')
            .attr("x", function (d) {
                return d.children || d._children ? -13 : 13;
            })
            .attr("dy", "-1.4em")
            .attr("text-anchor", function (d) {
                return d.children || d._children ? "end" : "start";
            })
            .text(function (d) {
                return d.info ? d.info : "";
            })
            .style("visibility", "hidden")
            .style("fill-opacity", 1e-6)
            .style("fill", "Tomato");

        // Transition nodes to their new position.
        let nodeUpdate = node.transition()
            .duration(duration)
            .attr("transform", function (d) {
                return "translate(" + d.y + "," + d.x + ")";
            });

        nodeUpdate.select("circle")
            .attr("r", 10)
            .style("fill", function (d) {
                return d._children ? "lightsteelblue" : "#fff";
            });

        nodeUpdate.select("text")
            .style("fill-opacity", 1);

        nodeUpdate.select("text.info")
            .style("fill-opacity", 1);

        // Transition exiting nodes to the parent's new position.
        let nodeExit = node.exit().transition()
            .duration(duration)
            .attr("transform", function (d) {
                return "translate(" + source.y + "," + source.x + ")";
            })
            .remove();

        nodeExit.select("circle")
            .attr("r", 1e-6);

        nodeExit.select("text")
            .style("fill-opacity", 1e-6);

        nodeExit.select("text.info")
            .style("fill-opacity", 1e-6);

        // Update the links…
        let link = svg.selectAll("path.link")
            .data(links, function (d) {
                return d.target.id;
            });

        // Enter any new links at the parent's previous position.
        link.enter().insert("path", "g")
            .attr("class", "link")
            .attr("d", function (d) {
                let o = {x: source.x0, y: source.y0};
                return diagonal({source: o, target: o});
            });

        // Transition links to their new position.
        link.transition()
            .duration(duration)
            .attr("d", diagonal);

        // Transition exiting nodes to the parent's new position.
        link.exit().transition()
            .duration(duration)
            .attr("d", function (d) {
                let o = {x: source.x, y: source.y};
                return diagonal({source: o, target: o});
            })
            .remove();

        // Stash the old positions for transition.
        nodes.forEach(function (d) {
            d.x0 = d.x;
            d.y0 = d.y;
        });

        node
            .on("mouseover", function (d) {
                d3.select(this)
                    .selectAll('text.info').style("visibility", "visible");
            })
            .on("mouseout", function (d) {
                d3.select(this)
                    .selectAll('text.info').style("visibility", "hidden");
            });


    }

    // Toggle children on click.
    function click(d) {
        if (d.children) {
            d._children = d.children;
            d.children = null;
        } else {
            d.children = d._children;
            d._children = null;
        }
        update(d);
    }

}

function parseJava() {
    const {parse} = require("java-ast");
    const javaText = `
        public class Main{
          public static void main(String args[]){
            int i = 0;
          }
        }
`;
    const ast = parse(javaText);
    console.log(ast);
    console.log(ast.toStringTree());
}

function realParseJava() {
    //TODO: Analyze the JSON cst
    const {parse} = require("java-parser");
    const javaText = `
public class Main{
  public static void main(String args[]){
    System.out.println("Hello World !");
  }
}
`;

    const cst = parse(javaText);
    console.log(cst);
}

window.onload = function () {
    document.getElementById("but").onclick = parseHTML;
}

function updateTree(obj)
{
    let valid = ['p','h1','h2','h3'];
    if (obj.children !== undefined)
    {
        obj.children = obj.children.filter(function(value, index, arr){
            return value.name !== undefined;
        });

        for (let i = 0; i < obj.children.length; i++) {
            updateTree(obj.children[i]);
        }
    }
}

function parseHTML() {
    let htmlToJson = require('html-to-json');
    let htmltext = document.getElementById("t1").value;

    htmlToJson.parse(htmltext, {

        p: function (doc) {
            console.log(doc[0]);
            treeData = {'name':'root', 'children': doc[0].children };
            console.log(treeData);
            updateTree(treeData);
            generateTree();
        }

    }).then(function (result) {

        console.log(result.p); // 'this is only an example'

    });
}

function parseJavascript() {
}

function parse() {

    realParseJava();
    parseHTML()
    let myPromise = asyncGetFile(fileName);
    myPromise.then((retrievedText) => {
        // treeData = JSON.parse(retrievedText);
        // console.log(treeData);
        // generateTree();
    }).catch(
        (reason) => {
            console.log('Handle rejected promise (' + reason + ') here.');
        });
}
