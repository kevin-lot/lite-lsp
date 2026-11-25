function greet -a who greeting --description 'Say hello'
    set -l message "$greeting, $who"
    echo $message
end
