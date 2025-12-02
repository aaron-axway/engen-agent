#!/bin/bash

# Delete the access request
echo "Deleting access request..."
axcentral delete accessrequests 8a2e901598f6ba0501991b1e5c9f0c02 -s aaron-agent-devlopment --forceDelete -y

# Check if delete was successful
if [ $? -eq 0 ]; then
    echo "Access request deleted successfully"
    
    # Apply the new access request
    echo "Applying new access request..."
    axcentral apply -f accessreq.yaml
    
    if [ $? -eq 0 ]; then
        echo "Access request applied successfully"
    else
        echo "Failed to apply access request"
        exit 1
    fi
else
    echo "Failed to delete access request"
    exit 1
fi